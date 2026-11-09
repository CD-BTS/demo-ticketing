param(
    [ValidateSet('pilot','comparison','report')][string]$Stage='pilot',
    [string]$Results='',
    [ValidateRange(8,120)][int]$Seconds=60,
    [ValidateRange(1,3)][int]$Repeats=3
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
if(-not $Results){
    if($Stage -ne 'pilot'){throw '-Results에 기존 예비 실행 결과 경로를 지정하세요.'}
    $Results=Join-Path $root ('build/queue-performance-'+[DateTime]::Now.ToString('yyyyMMdd-HHmmss'))
}
$saved=$env:PYTHONIOENCODING
try{
    $env:PYTHONIOENCODING='utf-8'
    if($Stage -eq 'report'){
        & python (Join-Path $PSScriptRoot 'queue-performance/report.py') $Results
        if($LASTEXITCODE -ne 0){throw '보고서 생성 실패'}
        return
    }
    # 빌드 JVM조차 시작하지 않도록 호스트 자원을 먼저 확인한다.
    & python -c "import sys; sys.path.insert(0,sys.argv[1]); from resources import Windows; w=Windows(); h=w.host(); w.close(); print('Available MiB:',round(h['availableBytes']/1024**2)); sys.exit(0 if h['availableBytes']>=2*1024**3 else 2)" (Join-Path $PSScriptRoot 'queue-performance')
    if($LASTEXITCODE -ne 0){throw '가용 RAM 2GiB 미달로 빌드·부하 테스트를 시작하지 않았습니다.'}
    Push-Location $root
    try{& .\gradlew.bat prepareQueuePerf --no-daemon; if($LASTEXITCODE -ne 0){throw '성능 전용 빌드 실패'}}finally{Pop-Location}
    $arguments=@((Join-Path $PSScriptRoot 'queue-performance/run.py'),'--stage',$Stage,'--results',$Results,'--seconds',"$Seconds",'--repeats',"$Repeats")
    if($Stage -eq 'pilot'){$arguments=@((Join-Path $PSScriptRoot 'queue-performance/run.py'),'--stage','pilot','--results',$Results,'--seconds','10','--repeats','1')}
    & python @arguments
    if($LASTEXITCODE -ne 0){throw "실행 중단 또는 실패. 보존 경로: $Results"}
}finally{
    $env:PYTHONIOENCODING=$saved
}
