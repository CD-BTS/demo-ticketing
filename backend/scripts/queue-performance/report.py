"""완료/중단 결과를 구분하는 오프라인 HTML·CSV 보고서. 없는 값은 추정하지 않는다."""
import csv
import html
import json
from pathlib import Path
import statistics
import sys

sys.stdout.reconfigure(encoding='utf-8')


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def lines(path):
    return [json.loads(row) for row in path.read_text(encoding='utf-8-sig').splitlines() if row] if path.exists() else []


def values(rows, *keys):
    found = []
    for row in rows:
        item = row
        for key in keys:
            item = item.get(key) if isinstance(item, dict) else None
        if isinstance(item, (float, int)):
            found.append(item)
    return found


def describe(samples):
    return {'samples': len(samples), 'avg': statistics.mean(samples) if samples else None,
            'min': min(samples) if samples else None, 'max': max(samples) if samples else None}


def render(value):
    return '미수집/미실행' if value is None else f'{value:.2f}' if isinstance(value, float) else html.escape(str(value))


def main():
    root = Path(sys.argv[1]).resolve()
    rows = load(root / 'results.json') if (root / 'results.json').exists() else []
    reports, resources = [], []
    for metadata_path in sorted(root.glob('*/*/*-environment.json')):
        metadata = load(metadata_path)
        prefix = Path(str(metadata_path).removesuffix('-environment.json'))
        samples = lines(Path(str(prefix) + '-resources.jsonl'))
        measured = [s for s in samples if s.get('phase') == 'measure']
        server = lines(Path(str(prefix) + '-details/server-metrics.jsonl'))
        window = [s for s in server if metadata.get('measurementStarted', '~') <= s['time'] <= metadata.get('measurementFinished', '')]
        resource = {'run': metadata['run'], 'mode': metadata['mode'], 'vus': metadata['vus'], 'repeat': metadata['repeat'],
            'status': metadata['status'], 'phase': 'measure' if measured else 'startup_only',
            'hostAvailableMiB': describe([v / 1024**2 for v in values(samples, 'host', 'availableBytes')]),
            'pagesInputPerSec': describe(values(samples, 'host', 'pagesInputPerSec')),
            'startupJavaCpuPercentHost': describe(values([s for s in samples if s.get('phase') == 'startup'], 'java', 'cpuPercentHost')),
            'startupJavaRssMiB': describe([v / 1024**2 for v in values([s for s in samples if s.get('phase') == 'startup'], 'java', 'rssBytes')]),
            'javaCpuPercentHost': describe(values(measured, 'java', 'cpuPercentHost')),
            'javaRssMiB': describe([v / 1024**2 for v in values(measured, 'java', 'rssBytes')]),
            'k6CpuPercentHost': describe(values(measured, 'k6', 'cpuPercentHost')),
            'k6RssMiB': describe([v / 1024**2 for v in values(measured, 'k6', 'rssBytes')]),
            'poolActive': describe(values(window, 'poolActive')), 'poolWaiters': describe(values(window, 'poolWaiters')),
            'dbConnected': describe(values(window, 'Threads_connected')), 'dbRunning': describe(values(window, 'Threads_running')),
            'dataLockWaits': describe(values(window, 'dataLockWaits')), 'queueObservedLength': describe(values(window, 'waiting'))}
        for role in ['mysql', 'redis']:
            cpu, memory = [], []
            for sample in measured:
                data = sample.get(role, {})
                if data.get('CPUPerc'):
                    cpu.append(float(data['CPUPerc'].rstrip('%')))
                if data.get('MemUsage'):
                    number = data['MemUsage'].split(' / ')[0]
                    for unit, multiplier in [('GiB', 1024), ('MiB', 1), ('KiB', 1 / 1024), ('B', 1 / 1024**2)]:
                        if number.endswith(unit):
                            memory.append(float(number[:-len(unit)]) * multiplier); break
            resource[role + 'CpuPercentOneCore'] = describe(cpu)
            resource[role + 'MemoryMiB'] = describe(memory)
        resources.append(resource)
        result_path = Path(str(prefix) + '-result.json')
        result = load(result_path) if result_path.exists() else {}
        dashboard = prefix.with_suffix('.html')
        reports.append({'mode': metadata['mode'], 'vus': metadata['vus'], 'repeat': metadata['repeat'], 'status': metadata['status'],
            'error': metadata.get('error'), 'dashboard': str(dashboard.relative_to(root)) if dashboard.exists() else None,
            'result': result, 'resource': resource})
    (root / 'resource-summary.json').write_text(json.dumps(resources, ensure_ascii=False, indent=2), encoding='utf-8')
    fields = ['stage', 'mode', 'vus', 'repeat', 'httpRps', 'bookingApiRps', 'bookingTps', 'apiAvgMs', 'apiP95Ms',
              'journeyP95Ms', 'queueAvgMs', 'queueP95Ms', 'http5xx', 'timeouts', 'businessRejections', 'dbViolations']
    with (root / 'comparison.csv').open('w', encoding='utf-8-sig', newline='') as file:
        writer = csv.DictWriter(file, fields, extrasaction='ignore'); writer.writeheader(); writer.writerows(rows)
    content = ['<!doctype html><html lang="ko"><meta charset="utf-8"><title>Issue #18 Redis 대기열 비교</title>',
        '<style>body{font:16px system-ui;margin:36px;line-height:1.6;color:#142536}table{border-collapse:collapse;margin:18px 0}td,th{border:1px solid #ccd5dd;padding:8px 12px}th{background:#e8f0f7}.stop{color:#b22}a{color:#175d9b}</style>',
        '<h1>Issue #18 Redis 대기열 A/B 결과</h1><p>없는 값은 0으로 대체하지 않습니다. API 지연·대기 시간·전체 예매 시간을 구분합니다.</p>',
        '<table><tr><th>환경</th><th>VU</th><th>반복</th><th>상태</th><th>예매 TPS</th><th>API p95 ms</th><th>전체 여정 p95 ms</th><th>대시보드</th></tr>']
    for report in reports:
        result = report['result']; dash = report['dashboard']
        content.append('<tr>' + ''.join('<td>' + render(value) + '</td>' for value in
            [report['mode'], report['vus'], report['repeat'], report['status'], result.get('bookingTps'), result.get('apiP95Ms'), result.get('journeyP95Ms')]) +
            ('<td><a href="' + html.escape(dash) + '">k6 HTML</a></td>' if dash else '<td>k6 미시작·미생성</td>') + '</tr>')
        if report['error']:
            content.append('<tr><td colspan="8" class="stop">중단 이유: ' + html.escape(report['error']) + '</td></tr>')
    content.append('</table><h2>자원 근거</h2>')
    for report in reports:
        r = report['resource']
        content.append(f'<h3>{html.escape(report["mode"])} / {report["vus"]} VU / {html.escape(r["phase"])}</h3>')
        content.append('<table><tr><th>지표</th><th>표본</th><th>평균</th><th>최소</th><th>최대</th></tr>')
        for key, value in r.items():
            if isinstance(value, dict):
                content.append('<tr><td>' + html.escape(key) + '</td>' + ''.join('<td>' + render(value.get(k)) + '</td>' for k in ['samples', 'avg', 'min', 'max']) + '</tr>')
        content.append('</table>')
    content.append('<p>Docker CPU는 코어 1개=100%, Java/k6 CPU는 호스트 전체=100%입니다. 시작 단계 표본을 성능 측정값으로 해석하지 마세요.</p></html>')
    (root / 'index.html').write_text('\n'.join(content), encoding='utf-8')
    print('오프라인 보고서: ' + str(root / 'index.html'))


if __name__ == '__main__':
    main()
