param([string]$BaseUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(10)
$results = New-Object System.Collections.Generic.List[object]

function Invoke-Check {
    param([string]$Method, [string]$Path, [int]$ExpectedStatus, [string]$Body)
    $request = New-Object System.Net.Http.HttpRequestMessage
    $request.Method = New-Object System.Net.Http.HttpMethod($Method)
    $request.RequestUri = [Uri]($BaseUrl.TrimEnd('/') + $Path)
    if ($Body) {
        $request.Content = New-Object System.Net.Http.StringContent($Body, [Text.Encoding]::UTF8, 'application/json')
    }
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    try {
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $json = $text | ConvertFrom-Json
        if ([int]$response.StatusCode -ne $ExpectedStatus) { throw "Unexpected status: $Method $Path" }
        $expectedCode = if ($ExpectedStatus -eq 200) { 'COMMON_200' } elseif ($ExpectedStatus -eq 401) { 'UNAUTHORIZED' } elseif ($ExpectedStatus -eq 404) { 'CONCERT_NOT_FOUND' } elseif ($ExpectedStatus -eq 405) { 'METHOD_NOT_ALLOWED' } else { 'INVALID_REQUEST' }
        if ($json.code -ne $expectedCode -or $json.success -ne ($ExpectedStatus -eq 200)) { throw "Envelope mismatch: $Method $Path" }
        $results.Add([pscustomobject]@{method=$Method;path=$Path;status=[int]$response.StatusCode;code=$json.code})
        return $json
    } finally { $response.Dispose(); $request.Dispose() }
}

try {
    $list = Invoke-Check GET '/api/v1/concerts' 200
    if (@($list.data).Count -ne 3) { throw 'Expected 3 concerts.' }
    $concertKeys = 'artist,hue,id,priceFrom,startAt,tagline,title,venue'
    $seatKeys = 'grade,heldByMe,id,number,price,row,status'
    foreach ($id in @('c1','c2','c3')) {
        $concert = (Invoke-Check GET ('/api/v1/concerts/'+$id) 200).data
        if ((($concert.PSObject.Properties.Name | Sort-Object) -join ',') -cne $concertKeys) { throw 'Concert field mismatch.' }
        if ($concert.id -cne $id -or $concert.priceFrom -ne 99000 -or $concert.startAt -notmatch 'Z$') { throw 'Concert identity/price/time mismatch.' }
        $seats = (Invoke-Check GET ('/api/v1/concerts/'+$id+'/seats') 200).data
        if (@($seats).Count -ne 96 -or $seats[0].id -cne 'A-01' -or $seats[95].id -cne 'H-12') { throw 'Seat count/order mismatch.' }
        foreach ($seat in $seats) {
            if ((($seat.PSObject.Properties.Name | Sort-Object) -join ',') -cne $seatKeys) { throw 'Seat field mismatch.' }
            if ($seat.status -cne 'AVAILABLE' -or $seat.heldByMe -ne $false) { throw 'Unexpected initial seat state.' }
        }
    }
    $null = Invoke-Check GET '/api/v1/concerts/missing' 404
    $null = Invoke-Check GET '/api/v1/concerts/missing/seats' 404
    $null = Invoke-Check GET ('/api/v1/concerts/'+('x'*51)) 400
    $null = Invoke-Check POST '/api/v1/concerts' 405 '{}'
    # Reservation APIs now exist and require an authenticated demo session.
    $null = Invoke-Check POST '/api/v1/bookings' 401 '{"holdId":"h_smoke","paymentIdentifier":"test-pay-shared-01"}'
    $null = Invoke-Check POST '/api/v1/concerts/c1/seat-holds' 401 '{"seatIds":["A-01"]}'
    $results | Format-Table -AutoSize
    $outputDirectory = Join-Path (Split-Path $PSScriptRoot) 'build/verification'
    $null = New-Item -ItemType Directory -Force $outputDirectory
    $report = [pscustomobject]@{checkedAt=[DateTime]::UtcNow.ToString('o');baseUrl=$BaseUrl;checks=$results.ToArray();bookingImplemented=$true}
    [IO.File]::WriteAllText((Join-Path $outputDirectory 'http-smoke.json'), ($report | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding($false)))
    Write-Output ('HTTP contract checks passed: '+$results.Count+'. Booking/hold require demo bearer authentication (401).')
} finally { $client.Dispose() }
