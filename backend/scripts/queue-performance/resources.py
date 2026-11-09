"""Windows/JVM/k6 및 Docker 자원 표본. 프로젝트 런타임 의존성은 추가하지 않는다."""
import ctypes as c
from ctypes import wintypes as w
import json
import os
import subprocess
import threading
import time
from datetime import datetime, timezone


def utc():
    return datetime.now(timezone.utc).isoformat()


class Memory(c.Structure):
    _fields_ = [('length', w.DWORD), ('load', w.DWORD)] + [(name, c.c_ulonglong) for name in
        ['total_phys', 'avail_phys', 'total_page', 'avail_page', 'total_virtual', 'avail_virtual', 'extended']]


class ProcessMemory(c.Structure):
    _fields_ = [('cb', w.DWORD), ('page_faults', w.DWORD)] + [(name, c.c_size_t) for name in
        ['peak_working', 'working', 'peak_paged', 'paged', 'peak_nonpaged', 'nonpaged', 'pagefile', 'peak_pagefile', 'private']]


class PdhValue(c.Structure):
    _fields_ = [('status', w.DWORD), ('value', c.c_double)]


class Windows:
    def __init__(self):
        self.kernel = c.WinDLL('kernel32', use_last_error=True)
        self.psapi = c.WinDLL('psapi', use_last_error=True)
        self.kernel.OpenProcess.restype = w.HANDLE
        self.kernel.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
        self.kernel.CloseHandle.argtypes = [w.HANDLE]
        self.kernel.GetProcessTimes.argtypes = [w.HANDLE] + [c.POINTER(w.FILETIME)] * 4
        self.psapi.GetProcessMemoryInfo.argtypes = [w.HANDLE, c.POINTER(ProcessMemory), w.DWORD]
        self.previous = {}
        self.pdh = c.WinDLL('pdh')
        self.query, self.counter = w.HANDLE(), w.HANDLE()
        self.pdh.PdhOpenQueryW.argtypes = [w.LPCWSTR, c.c_size_t, c.POINTER(w.HANDLE)]
        self.pdh.PdhAddEnglishCounterW.argtypes = [w.HANDLE, w.LPCWSTR, c.c_size_t, c.POINTER(w.HANDLE)]
        self.pdh.PdhCollectQueryData.argtypes = [w.HANDLE]
        self.pdh.PdhGetFormattedCounterValue.argtypes = [w.HANDLE, w.DWORD, c.POINTER(w.DWORD), c.POINTER(PdhValue)]
        self.pdh.PdhCloseQuery.argtypes = [w.HANDLE]
        self.paging = self.pdh.PdhOpenQueryW(None, 0, c.byref(self.query)) == 0 and self.pdh.PdhAddEnglishCounterW(
            self.query, r'\Memory\Pages Input/sec', 0, c.byref(self.counter)) == 0
        if self.paging:
            self.pdh.PdhCollectQueryData(self.query)

    @staticmethod
    def ticks(value):
        return (value.dwHighDateTime << 32) + value.dwLowDateTime

    def host(self):
        memory = Memory(); memory.length = c.sizeof(memory)
        if not self.kernel.GlobalMemoryStatusEx(c.byref(memory)):
            raise OSError('호스트 메모리 표본 수집 실패')
        value = None
        if self.paging and self.pdh.PdhCollectQueryData(self.query) == 0:
            counter = PdhValue()
            if self.pdh.PdhGetFormattedCounterValue(self.counter, 0x200, None, c.byref(counter)) == 0 and counter.status in (0, 1):
                value = counter.value
        return {'totalBytes': memory.total_phys, 'availableBytes': memory.avail_phys,
                'commitAvailableBytes': memory.avail_page, 'pagesInputPerSec': value}

    def process(self, pid):
        if not pid:
            return None
        handle = self.kernel.OpenProcess(0x1000 | 0x10, False, pid)
        if not handle:
            return {'missing': 'process 종료 또는 조회 권한 없음'}
        try:
            created, exited, kernel, user = (w.FILETIME() for _ in range(4))
            if not self.kernel.GetProcessTimes(handle, c.byref(created), c.byref(exited), c.byref(kernel), c.byref(user)):
                return {'missing': 'CPU 조회 실패'}
            cpu = (self.ticks(kernel) + self.ticks(user)) / 10_000_000
            now = time.monotonic()
            previous = self.previous.get(pid)
            self.previous[pid] = (now, cpu)
            usage = None if previous is None else 100 * (cpu - previous[1]) / (now - previous[0]) / os.cpu_count()
            memory = ProcessMemory(); memory.cb = c.sizeof(memory)
            ok = self.psapi.GetProcessMemoryInfo(handle, c.byref(memory), c.sizeof(memory))
            return {'cpuPercentHost': usage, 'rssBytes': memory.working if ok else None,
                    'privateBytes': memory.private if ok else None}
        finally:
            self.kernel.CloseHandle(handle)

    def close(self):
        if self.query:
            self.pdh.PdhCloseQuery(self.query)


class Sampler:
    def __init__(self, output, java_pid, mysql, redis):
        self.output, self.java_pid = output, java_pid
        self.k6_pid, self.phase = None, 'startup'
        self.stop_event, self.unsafe = threading.Event(), threading.Event()
        self.reason = None
        self.latest = {}
        self.lock = threading.Lock()
        self.stats = subprocess.Popen(['docker', 'stats', '--format', '{{json .}}', mysql, redis],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding='utf-8', creationflags=subprocess.CREATE_NO_WINDOW)
        self.names = {mysql: 'mysql', redis: 'redis'}
        self.reader = threading.Thread(target=self.read_stats, daemon=True)
        self.thread = threading.Thread(target=self.run, daemon=True)

    def read_stats(self):
        for line in self.stats.stdout:
            try:
                row = json.loads(line)
                role = self.names.get(row.get('Name'))
                if role:
                    with self.lock:
                        self.latest[role] = {'at': utc(), **row}
            except (ValueError, KeyError):
                pass

    def start(self):
        self.reader.start(); self.thread.start()

    def run(self):
        windows = Windows()
        low = paging = generator = 0
        try:
            with self.output.open('a', encoding='utf-8') as file:
                while not self.stop_event.is_set():
                    row = {'time': utc(), 'phase': self.phase, 'host': windows.host(),
                           'java': windows.process(self.java_pid), 'k6': windows.process(self.k6_pid)}
                    with self.lock:
                        row.update(self.latest)
                    file.write(json.dumps(row, ensure_ascii=False) + '\n'); file.flush()
                    free = row['host']['availableBytes']
                    low = low + 1 if free < 768 * 1024**2 else 0
                    pages = row['host']['pagesInputPerSec']
                    paging = paging + 1 if free < 1024**3 and pages is not None and pages > 1000 else 0
                    k6_cpu = (row['k6'] or {}).get('cpuPercentHost')
                    generator = generator + 1 if k6_cpu is not None and k6_cpu > 80 else 0
                    if low >= 3 or paging >= 5 or generator >= 5:
                        self.reason = '가용 RAM 부족' if low >= 3 else '지속적인 페이징' if paging >= 5 else 'k6 CPU 병목'
                        self.unsafe.set()
                    self.stop_event.wait(1)
        except Exception as error:
            self.reason = '자원 수집 실패: ' + type(error).__name__; self.unsafe.set()
        finally:
            windows.close()

    def close(self):
        self.stop_event.set(); self.thread.join(5)
        if self.stats.poll() is None:
            self.stats.terminate(); self.stats.wait(5)
        self.reader.join(2)
