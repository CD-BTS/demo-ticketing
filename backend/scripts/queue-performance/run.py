"""격리 A/B 실행기. Windows + Python 표준 라이브러리만 사용한다."""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile
from resources import Sampler, Windows, utc

sys.stdout.reconfigure(encoding='utf-8')
sys.stderr.reconfigure(encoding='utf-8')

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def command(args):
    result = subprocess.run(args, capture_output=True, text=True, encoding='utf-8', errors='replace', creationflags=subprocess.CREATE_NO_WINDOW)
    if result.returncode:
        raise RuntimeError(f'{args[0]} {args[1]} 실패: ' + result.stderr[-1000:])
    return result.stdout.strip()


def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def metric(summary, name, field='count'):
    return summary.get('metrics', {}).get(name, {}).get('values', {}).get(field)


def api(base, method, path, token=None, body=None, expected=(200, 201)):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(base + '/api/v1' + path, headers=headers, method=method,
        data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            status, content = response.status, response.read()
    except urllib.error.HTTPError as response:
        status, content = response.code, response.read()
    payload = json.loads(content)
    if status not in expected:
        raise RuntimeError(f'API 검증 실패: {method} {path}: {status} {payload.get("code")}')
    return status, payload


class Experiment:
    def __init__(self, destination, mode, vus, repeat, seconds, stage):
        self.run = secrets.token_hex(6)
        self.mode, self.vus, self.repeat, self.seconds, self.stage = mode, vus, repeat, seconds, stage
        self.folder = destination / stage / mode
        self.folder.mkdir(parents=True, exist_ok=True)
        self.prefix = self.folder / f'{vus}vu-run{repeat}'
        if self.prefix.with_suffix('.html').exists():
            raise RuntimeError('기존 결과를 덮어쓰지 않습니다: ' + str(self.prefix))
        self.detail = Path(str(self.prefix) + '-details')
        self.detail.mkdir()
        self.containers = []
        self.java = self.sampler = None
        self.private = []
        self.env = os.environ.copy()
        self.runtime = {'run': self.run, 'mode': mode, 'vus': vus, 'repeat': repeat, 'stage': stage,
            'seconds': seconds, 'started': utc(), 'queueCapacity': 100, 'queueAdmitTtl': 'PT5M',
            'explicitClientLeave': True, 'serverAutoRelease': False, 'thinkSeconds': 1,
            'warmup': '동일 VU별 정확히 5개 예매 후 최소 15초 워밍업 구간', 'pollSeconds': 0.5,
            'javaHeapMaxMiB': 512, 'hikariPoolMax': 10, 'tomcatMaxThreads': 200,
            'mysqlCpuLimit': 2, 'mysqlMemoryMiB': 1024, 'redisCpuLimit': 1, 'redisMemoryMiB': 256,
            'metrics': 'process CPU는 호스트 전체 논리 CPU=100%; Docker CPU는 논리 CPU 1개=100%',
            'monitorDbConnections': 1, 'schedulers': False, 'btsDelivery': False}

    def file(self, suffix):
        return Path(str(self.prefix) + suffix)

    def docker(self, *args):
        return command(['docker', *args])

    def container(self, role, image, tcp, extra=(), tail=()):
        name = f'demo-queue18-{role}-{self.run}'
        identity = self.docker('run', '--detach', '--rm', '--name', name, '--label', 'demo-ticketing.issue=18',
            '--publish', f'127.0.0.1::{tcp}', *extra, image, *tail)
        self.containers.append((name, identity))
        binding = self.docker('port', name, f'{tcp}/tcp')
        if not binding.startswith('127.0.0.1:'):
            raise RuntimeError('loopback 전용 컨테이너가 아닙니다.')
        return name, int(binding.split(':')[-1])

    def sql(self, sql):
        return self.docker('exec', self.mysql, 'mysql', '--user=root', '--skip-password', '--batch', '--raw',
            '--database=demo_ticketing_db', '--execute=' + sql)

    def prepare(self):
        username, password = 'perf_' + self.run, secrets.token_hex(16)
        extra = ['--memory=1g', '--cpus=2', '--env', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', '--env',
            'MYSQL_DATABASE=demo_ticketing_db', '--env', 'MYSQL_USER=' + username, '--env', 'MYSQL_PASSWORD=' + password]
        for source, target in [('schema.sql', '10-schema.sql'), ('seed.sql', '20-seed.sql'),
                               ('02-booking-support.sql', '30-booking-support.sql'), ('03-seat-concurrency.sql', '40-seat-concurrency.sql')]:
            extra += ['--mount', f'type=bind,source={ROOT / "db" / source},target=/docker-entrypoint-initdb.d/{target},readonly']
        self.mysql, mysql_port = self.container('mysql', 'mysql:8.0', 3306, extra,
            ['--innodb-buffer-pool-size=134217728', '--max-connections=40', '--character-set-server=utf8mb4',
             '--collation-server=utf8mb4_0900_as_cs', '--default-time-zone=+00:00'])
        self.redis, redis_port = self.container('redis', 'redis:7.4-alpine', 6379, ['--memory=256m', '--cpus=1'],
            ['redis-server', '--appendonly', 'yes'])
        for _ in range(120):
            try:
                if 'MySQL init process done' in self.docker('logs', self.mysql) and self.sql(
                    "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='demo_ticketing_db'").splitlines()[-1] == '11':
                    break
            except RuntimeError:
                pass
            time.sleep(0.5)
        else:
            raise RuntimeError('격리 MySQL 초기화 시간 초과')
        self.sql(f"GRANT SELECT ON performance_schema.* TO '{username}'@'%';")
        redis_info = dict(line.split(':', 1) for line in self.docker('exec', self.redis, 'redis-cli', 'INFO', 'server').splitlines() if ':' in line)
        app_port, dashboard_port = port(), port()
        self.base = f'http://127.0.0.1:{app_port}'
        self.env.update({'PERF_RUN': self.run, 'PERF_MODE': self.mode, 'PERF_VUS': str(self.vus), 'PERF_SECONDS': str(self.seconds),
            'PERF_OUTPUT': str(self.detail), 'PERF_REDIS_RUN_ID': redis_info['run_id'],
            'DB_HOST': '127.0.0.1', 'DB_PORT': str(mysql_port), 'DB_USERNAME': username, 'DB_PASSWORD': password,
            'SPRING_DATASOURCE_URL': f'jdbc:mysql://127.0.0.1:{mysql_port}/demo_ticketing_db?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8',
            'SPRING_DATASOURCE_USERNAME': username, 'SPRING_DATASOURCE_PASSWORD': password,
            'REDIS_HOST': '127.0.0.1', 'REDIS_PORT': str(redis_port), 'REDIS_DATABASE': '0', 'REDIS_USERNAME': '', 'REDIS_PASSWORD': '',
            'SPRING_DATA_REDIS_HOST': '127.0.0.1', 'SPRING_DATA_REDIS_PORT': str(redis_port), 'SPRING_DATA_REDIS_PASSWORD': '', 'SPRING_DATA_REDIS_USERNAME': '',
            'BTS_ENABLED': 'false', 'BTS_SERVICE_ID': 'perf-' + self.run, 'DEMO_JOBS_ENABLED': 'false',
            'POLICY_DECISION_MODE': 'disabled', 'CAPTCHA_MODE': 'disabled', 'QUEUE_KEY_PREFIX': 'demo-ticketing:perf:' + self.run,
            'QUEUE_CAPACITY': '100', 'QUEUE_ADMIT_TTL': 'PT5M', 'HOLD_DURATION': 'PT5M',
            'TEST_PAYMENT_IDENTIFIERS': 'test-pay-shared-01', 'JPA_SHOW_SQL': 'false',
            'K6_WEB_DASHBOARD': 'true', 'K6_WEB_DASHBOARD_HOST': '127.0.0.1', 'K6_WEB_DASHBOARD_PORT': str(dashboard_port),
            'K6_WEB_DASHBOARD_PERIOD': '1s', 'K6_WEB_DASHBOARD_OPEN': 'false'})
        self.runtime.update({'mysql': self.mysql, 'mysqlPort': mysql_port, 'redis': self.redis, 'redisPort': redis_port,
            'baseUrl': self.base, 'dashboardUrl': f'http://127.0.0.1:{dashboard_port}', 'logicalProcessors': os.cpu_count(),
            'k6Version': command(['k6', 'version']), 'database': self.sql('SELECT VERSION()').splitlines()[-1]})
        save(self.file('-environment.json'), self.runtime)
        classpath = (ROOT / 'build/queue-perf/classpath.txt').read_text(encoding='utf-8-sig')
        args = ['java', '-Xms128m', '-Xmx512m', '-XX:MaxMetaspaceSize=192m', '-XX:MaxDirectMemorySize=128m', '-cp', classpath,
            'com.bts.queueperf.QueuePerformanceApplication', '--server.address=127.0.0.1', f'--server.port={app_port}',
            '--spring.datasource.hikari.maximum-pool-size=10', '--spring.datasource.hikari.minimum-idle=10',
            '--server.tomcat.threads.max=200', '--spring.threads.virtual.enabled=false']
        self.server_log = self.file('-server.log').open('w', encoding='utf-8')
        self.sampler = Sampler(self.file('-resources.jsonl'), None, self.mysql, self.redis)
        self.sampler.start()
        time.sleep(3)
        if self.sampler.unsafe.is_set():
            raise RuntimeError(self.sampler.reason)
        self.java = subprocess.Popen(args, cwd=ROOT, env=self.env, stdout=self.server_log, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW)
        self.sampler.java_pid = self.java.pid
        self.runtime['serverPid'] = self.java.pid
        save(self.file('-environment.json'), self.runtime)
        for _ in range(180):
            if self.java.poll() is not None:
                raise RuntimeError('성능 서버 시작 실패: ' + str(self.file('-server.log')))
            if self.sampler.unsafe.is_set():
                raise RuntimeError(self.sampler.reason)
            if (self.detail / 'server-ready.json').exists():
                break
            time.sleep(0.5)
        else:
            raise RuntimeError('서버·데이터 준비 시간 초과')
        self.concerts = read(self.detail / 'server-ready.json')['concerts']
        self.tokens = []
        for index in range(self.vus + 2):
            _, response = api(self.base, 'POST', '/users', body={'userId': f'p18_{self.run}_{index}'})
            self.tokens.append(response['data']['token'])
        windows = Windows()
        try:
            host = windows.host()
        finally:
            windows.close()
        self.runtime.update({'mysql': self.mysql, 'mysqlPort': mysql_port, 'redis': self.redis, 'redisPort': redis_port,
            'serverPid': self.java.pid, 'baseUrl': self.base, 'dashboardUrl': f'http://127.0.0.1:{dashboard_port}',
            'logicalProcessors': os.cpu_count(), 'host': host, 'k6Version': command(['k6', 'version']),
            'database': self.sql('SELECT VERSION()').splitlines()[-1],
            'dockerLimits': json.loads(self.docker('info', '--format', '{{json .}}')).get('MemTotal')})
        save(self.file('-environment.json'), self.runtime)

    def verify_api(self):
        concert = self.concerts['verify']; token, other = self.tokens[:2]
        endpoint = f'/concerts/{concert}/seat-holds'
        checks = []
        status, _ = api(self.base, 'POST', endpoint, body={'seatIds': ['S000001']}, expected=(401,))
        checks.append({'check': '미인증 선점 거부', 'status': status})
        if self.mode == 'redis-queue':
            status, response = api(self.base, 'POST', endpoint, token, {'seatIds': ['S000001']}, expected=(403,))
            if response.get('code') != 'QUEUE_NOT_ADMITTED':
                raise RuntimeError('B 입장 검사 우회 발생')
            checks.append({'check': '미입장 선점 거부', 'status': status})
            api(self.base, 'POST', f'/concerts/{concert}/queue', token)
        _, response = api(self.base, 'POST', endpoint, token, {'seatIds': ['S000001']})
        hold = response['data']['holdId']
        status, _ = api(self.base, 'POST', '/bookings', other, {'holdId': hold, 'paymentIdentifier': 'test-pay-shared-01'}, expected=(403,))
        checks.append({'check': '타인 선점 확정 거부', 'status': status})
        body = {'holdId': hold, 'paymentIdentifier': 'test-pay-shared-01'}
        _, first = api(self.base, 'POST', '/bookings', token, body, expected=(201,))
        _, second = api(self.base, 'POST', '/bookings', token, body, expected=(200,))
        if first['data']['id'] != second['data']['id']:
            raise RuntimeError('중복 확정 멱등성 실패')
        checks.append({'check': '정상 확정·중복 확정', 'status': [201, 200]})
        if self.mode == 'redis-queue':
            api(self.base, 'DELETE', f'/concerts/{concert}/queue/me', token)
        save(self.file('-api-checks.json'), checks)

    def k6(self, phase):
        input_file = self.detail / f'{phase}-private-input.json'
        save(input_file, {'base': self.base, 'tokens': self.tokens[:self.vus], 'concert': self.concerts[phase]})
        self.private.append(input_file)
        summary_path = self.file('-warmup-summary.json' if phase == 'warmup' else '-summary.json')
        html_path = self.file('-warmup.html' if phase == 'warmup' else '.html')
        env = {**self.env, 'PERF_PHASE': phase, 'PERF_INPUT': str(input_file), 'PERF_SUMMARY': str(summary_path),
               'K6_WEB_DASHBOARD_EXPORT': str(html_path)}
        self.sampler.phase = phase
        before = time.monotonic()
        with self.file('-' + phase + '-k6.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(['k6', 'run', '--quiet', str(HERE / 'journey.js')], cwd=ROOT, env=env,
                stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
            self.sampler.k6_pid = process.pid
            while process.poll() is None:
                if self.sampler.unsafe.is_set() or time.monotonic() - before > (120 if phase == 'warmup' else self.seconds + 75):
                    process.terminate(); process.wait(10)
                    raise RuntimeError(self.sampler.reason or 'k6 실행 시간 초과')
                time.sleep(0.5)
        self.sampler.k6_pid = None
        input_file.unlink(); self.private.remove(input_file)
        if not summary_path.exists() or not html_path.exists() or html_path.stat().st_size < 10000:
            raise RuntimeError('k6 JSON/HTML 대시보드 결과가 생성되지 않았습니다.')
        summary = read(summary_path)
        if process.returncode:
            raise RuntimeError('k6 임계값/실행 실패: ' + str(self.file('-' + phase + '-k6.log')))
        if phase == 'warmup' and metric(summary, 'booking_created') != self.vus * 5:
            raise RuntimeError('고정 워밍업 예매 수 불일치')
        return summary, time.monotonic() - before

    def verify_db(self, summary):
        query = (ROOT / 'scripts/seat-concurrency/verify.sql').read_text(encoding='utf-8-sig').replace('__CONCERT__', self.concerts['measure'])
        db = json.loads(self.sql(query).splitlines()[-1]); save(self.file('-db.json'), db)
        expected = metric(summary, 'booking_created')
        if db['violations'] or not all(db[key] == expected for key in ['booked', 'holds', 'bookings', 'tickets', 'outbox']) or db['activeHolds']:
            raise RuntimeError('HTTP 신규 예매와 DB 결과 또는 정합성 불일치')
        base = self.env['QUEUE_KEY_PREFIX'] + ':{' + __import__('base64').urlsafe_b64encode(self.concerts['measure'].encode()).decode().rstrip('=') + '}:'
        state = {key: int(self.docker('exec', self.redis, 'redis-cli', '--raw', 'ZCARD', base + key))
                 for key in ['waiting', 'waiting-delayed', 'admitted']}
        save(self.file('-redis-final.json'), state)
        if any(state.values()):
            raise RuntimeError('명시적 leave 후 Redis 대기/입장 자리가 남았습니다.')
        return db

    def execute(self):
        print(f'시작: {self.stage} {self.mode} {self.vus}VU 반복{self.repeat}', flush=True)
        try:
            self.prepare(); self.verify_api()
            print(f'대시보드: {self.runtime["dashboardUrl"]} / 결과: {self.prefix}', flush=True)
            warmup_start = time.monotonic(); self.k6('warmup')
            self.sampler.phase = 'settle'
            time.sleep(max(2, 15 - (time.monotonic() - warmup_start)))
            self.runtime['measurementStarted'] = utc(); save(self.file('-environment.json'), self.runtime)
            summary, elapsed = self.k6('measure')
            self.runtime['measurementFinished'] = utc(); self.runtime['observedSeconds'] = elapsed
            self.sampler.phase = 'audit'; time.sleep(2)
            db = self.verify_db(summary)
            row = {'stage': self.stage, 'mode': self.mode, 'vus': self.vus, 'repeat': self.repeat, 'run': self.run,
                'durationSeconds': self.seconds, 'observedSeconds': elapsed, 'prefix': str(self.prefix.relative_to(ROOT)),
                'httpRps': metric(summary, 'window_http_requests') / self.seconds,
                'bookingApiRps': metric(summary, 'window_booking_requests') / self.seconds,
                'bookingTps': metric(summary, 'window_booking_created') / self.seconds,
                'apiAvgMs': metric(summary, 'api_latency', 'avg'), 'apiP95Ms': metric(summary, 'api_latency', 'p(95)'),
                'journeyAvgMs': metric(summary, 'booking_journey', 'avg'), 'journeyP95Ms': metric(summary, 'booking_journey', 'p(95)'),
                'queueAvgMs': metric(summary, 'queue_wait', 'avg'), 'queueP95Ms': metric(summary, 'queue_wait', 'p(95)'),
                'processingP95Ms': metric(summary, 'booking_processing', 'p(95)'), 'created': metric(summary, 'booking_created'),
                'http5xx': metric(summary, 'http_5xx'), 'timeouts': metric(summary, 'timeouts'),
                'networkFailures': metric(summary, 'network_failures'), 'businessRejections': metric(summary, 'business_rejections'),
                'http5xxRate': metric(summary, 'http_5xx') / metric(summary, 'http_reqs'), 'dbViolations': db['violations']}
            row['apis'] = {name: {'avgMs': metric(summary, f'api_latency{{api:{name}}}', 'avg'),
                'p95Ms': metric(summary, f'api_latency{{api:{name}}}', 'p(95)'),
                'samples': metric(summary, f'api_latency{{api:{name}}}', 'count')}
                for name in ['hold', 'confirm', 'queue_join', 'queue_leave']}
            self.runtime['status'] = 'passed'; save(self.file('-environment.json'), self.runtime)
            save(self.file('-result.json'), row)
            print(f'통과: {self.mode} {self.vus}VU #{self.repeat}: TPS={row["bookingTps"]:.2f}, 업무 p95={row["processingP95Ms"]:.2f}ms, DB 위반=0', flush=True)
            return row
        except Exception as error:
            self.runtime.update({'status': 'failed_or_resource_stopped', 'error': str(error), 'finished': utc()})
            save(self.file('-environment.json'), self.runtime)
            # 취소된 HTTP도 DB에 커밋될 수 있으므로 실패한 실행의 DB 결과도 남긴다.
            if hasattr(self, 'concerts'):
                try:
                    query = (ROOT / 'scripts/seat-concurrency/verify.sql').read_text(encoding='utf-8-sig').replace('__CONCERT__', self.concerts['measure'])
                    save(self.file('-db.json'), json.loads(self.sql(query).splitlines()[-1]))
                except Exception:
                    pass
            raise
        finally:
            if self.sampler:
                self.sampler.close()
            if self.java and self.java.poll() is None:
                self.java.terminate(); self.java.wait(15)
            if hasattr(self, 'server_log'):
                self.server_log.close()
            for file in self.private:
                file.unlink(missing_ok=True)
            for name, identity in reversed(self.containers):
                if name.startswith('demo-queue18-') and self.run in name and self.docker('inspect', '--format={{.Id}}', name) == identity:
                    self.docker('stop', '--timeout', '10', name)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--stage', choices=['pilot', 'comparison'], required=True)
    parser.add_argument('--results', required=True)
    parser.add_argument('--seconds', type=int, default=60)
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--vus', type=int, nargs='+')
    args = parser.parse_args()
    vus = args.vus or ([1, 10] if args.stage == 'pilot' else [10, 50, 100])
    if any(v not in [1, 10, 50, 100] for v in vus) or not 8 <= args.seconds <= 120 or not 1 <= args.repeats <= 3:
        raise ValueError('이번 단계에서 허용한 범위를 벗어났습니다.')
    destination = Path(args.results).resolve()
    destination.relative_to(ROOT / 'build')
    destination.mkdir(parents=True, exist_ok=True)
    windows = Windows()
    try:
        available = windows.host()
    finally:
        windows.close()
    save(destination / 'host-preflight.json', {'checkedAt': utc(), 'host': available, 'minimumAvailableBytes': 2 * 1024**3})
    if available['availableBytes'] < 2 * 1024**3:
        raise RuntimeError('시작 전 가용 RAM 2GiB 미달: 다른 작업을 직접 정리하거나 별도 부하 발생기를 사용하세요. 테스트를 시작하지 않습니다.')
    if args.stage == 'comparison':
        prior = read(destination / 'results.json') if (destination / 'results.json').exists() else []
        observed = {(r['mode'], r['vus']) for r in prior if r.get('stage') == 'pilot'}
        if not all((mode, v) in observed for mode in ['no-queue', 'redis-queue'] for v in [1, 10]):
            raise RuntimeError('같은 결과 디렉터리에 1/10 VU A/B 예비 실행 4개의 성공 결과가 필요합니다.')
    jar = Path((ROOT / 'build/queue-perf/bootjar.txt').read_text(encoding='utf-8-sig'))
    with zipfile.ZipFile(jar) as archive:
        leaked = [n for n in archive.namelist() if 'queueperf/' in n or 'PerformanceQueueService' in n]
        if leaked:
            raise RuntimeError('운영 JAR에 우회 클래스가 포함되었습니다.')
    save(destination / 'production-jar-check.json', {'jar': jar.name, 'perfClassesInJar': leaked,
        'sha256': __import__('hashlib').sha256(jar.read_bytes()).hexdigest(), 'checkedAt': utc()})
    rows = read(destination / 'results.json') if (destination / 'results.json').exists() else []
    for v in vus:
        for repeat in range(1, args.repeats + 1):
            modes = ['no-queue', 'redis-queue'] if repeat % 2 else ['redis-queue', 'no-queue']
            for mode in modes:
                row = Experiment(destination, mode, v, repeat, args.seconds, args.stage).execute()
                rows.append(row); save(destination / 'results.json', rows)
    print('완료: ' + str(destination), flush=True)


if __name__ == '__main__':
    main()
