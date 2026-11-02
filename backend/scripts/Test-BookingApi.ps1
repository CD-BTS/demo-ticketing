param([string]$BaseUrl = 'http://localhost:8080', [switch]$CheckExpiry)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Net.Http
$client=New-Object System.Net.Http.HttpClient
$client.Timeout=[TimeSpan]::FromSeconds(15)
$results=New-Object System.Collections.Generic.List[object]
$cleanup=New-Object System.Collections.Generic.List[object]
function Send-Request {
    param([string]$Method,[string]$Path,[string]$Token,[object]$Body)
    $req=New-Object System.Net.Http.HttpRequestMessage
    $req.Method=New-Object System.Net.Http.HttpMethod($Method)
    $req.RequestUri=[Uri]($BaseUrl.TrimEnd('/')+$Path)
    if($Token){$req.Headers.Add('Authorization','Bearer '+$Token)}
    $req.Headers.Add('X-Forwarded-For','203.0.113.99')
    if($null -ne $Body){$raw=$Body|ConvertTo-Json -Depth 8 -Compress; $req.Content=New-Object System.Net.Http.StringContent($raw,[Text.Encoding]::UTF8,'application/json')}
    $task=$client.SendAsync($req)
    return [pscustomobject]@{Task=$task;Request=$req;Method=$Method;Path=$Path}
}
function Finish-Request {
    param($Pending,[int]$Expected)
    $res=$Pending.Task.GetAwaiter().GetResult()
    try {
        $json=$res.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json
        if($Expected -and [int]$res.StatusCode -ne $Expected){throw ('Unexpected status: '+$Pending.Method+' '+$Pending.Path+' '+[int]$res.StatusCode+' '+$json.code)}
        $results.Add([pscustomobject]@{method=$Pending.Method;path=$Pending.Path;status=[int]$res.StatusCode;code=$json.code})
        return [pscustomobject]@{Status=[int]$res.StatusCode;Json=$json}
    } finally {$res.Dispose();$Pending.Request.Dispose()}
}
function Request {
    param([string]$Method,[string]$Path,[int]$Expected,[string]$Token,[object]$Body)
    return Finish-Request (Send-Request $Method $Path $Token $Body) $Expected
}
function Confirm {
    param([string]$Hold,[string]$Token,[int]$Expected=201)
    $r=Request POST '/api/v1/bookings' $Expected $Token @{holdId=$Hold;paymentIdentifier='test-pay-shared-01'}
    $cleanup.Add([pscustomobject]@{Booking=$r.Json.data.id;Token=$Token})
    return $r.Json.data
}
try {
    $suffix=[Guid]::NewGuid().ToString('N').Substring(0,16)
    $a=(Request POST '/api/v1/users' 200 '' @{userId=('http_a_'+$suffix)}).Json.data
    $b=(Request POST '/api/v1/users' 200 '' @{userId=('http_b_'+$suffix)}).Json.data
    $available=@((Request GET '/api/v1/concerts/c1/seats' 200 '' $null).Json.data|Where-Object status -eq 'AVAILABLE')
    if($available.Count -lt 3){throw 'At least 3 available seed seats are required.'}
    $seat1=$available[0].id;$seat2=$available[1].id;$seat3=$available[2].id
    $null=Request POST '/api/v1/concerts/c1/seat-holds' 401 '' @{seatIds=@($seat1)}
    $h=(Request POST '/api/v1/concerts/c1/seat-holds' 201 $a.token @{seatIds=@($seat1,$seat2)}).Json.data
    $cleanup.Add([pscustomobject]@{Hold=$h.holdId;Token=$a.token})
    $null=Request POST '/api/v1/concerts/c1/seat-holds' 409 $b.token @{seatIds=@($seat1)}
    $mine=(Request GET '/api/v1/concerts/c1/seats' 200 $a.token $null).Json.data
    if(-not @($mine|Where-Object { $_.id -eq $seat1 })[0].heldByMe){throw 'Missing hold ownership.'}
    $null=Request POST '/api/v1/bookings' 403 $b.token @{holdId=$h.holdId;paymentIdentifier='test-pay-shared-01'}
    $booking=Confirm $h.holdId $a.token
    $again=Confirm $h.holdId $a.token 200
    if($booking.id -ne $again.id){throw 'Duplicate booking was created.'}
    $null=Request GET '/api/v1/bookings/me' 200 $a.token $null
    $null=Request POST ('/api/v1/bookings/'+$booking.id+'/cancel') 403 $b.token $null
    $cancelled=(Request POST ('/api/v1/bookings/'+$booking.id+'/cancel') 200 $a.token $null).Json.data
    if($cancelled.status -ne 'CANCELLED'){throw 'Cancel did not change state.'}
    $null=Request POST ('/api/v1/bookings/'+$booking.id+'/cancel') 200 $a.token $null

    # Resale + retry of an old cancellation cannot free the new owner's seat.
    $resale=(Request POST '/api/v1/concerts/c1/seat-holds' 201 $b.token @{seatIds=@($seat1)}).Json.data
    $cleanup.Add([pscustomobject]@{Hold=$resale.holdId;Token=$b.token})
    $new=Confirm $resale.holdId $b.token
    $null=Request POST ('/api/v1/bookings/'+$booking.id+'/cancel') 200 $a.token $null
    $states=(Request GET '/api/v1/concerts/c1/seats' 200 '' $null).Json.data
    if(@($states|Where-Object id -eq $seat1)[0].status -ne 'BOOKED'){throw 'Old cancel corrupted resale.'}
    $null=Request POST ('/api/v1/bookings/'+$new.id+'/cancel') 200 $b.token $null

    # Both requests are sent before awaiting either result.
    $p1=Send-Request POST '/api/v1/concerts/c1/seat-holds' $a.token @{seatIds=@($seat3)}
    $p2=Send-Request POST '/api/v1/concerts/c1/seat-holds' $b.token @{seatIds=@($seat3)}
    $r1=Finish-Request $p1 0;$r2=Finish-Request $p2 0
    if((@($r1.Status,$r2.Status)|Sort-Object)-join ',' -ne '201,409'){throw 'Concurrent holds did not have exactly one winner.'}
    $winner=if($r1.Status -eq 201){$r1}else{$r2}
    $token=if($r1.Status -eq 201){$a.token}else{$b.token}
    $holdId=$winner.Json.data.holdId
    $cleanup.Add([pscustomobject]@{Hold=$holdId;Token=$token})
    $body=@{holdId=$holdId;paymentIdentifier='test-pay-shared-01'}
    $p1=Send-Request POST '/api/v1/bookings' $token $body
    $p2=Send-Request POST '/api/v1/bookings' $token $body
    $r1=Finish-Request $p1 0;$r2=Finish-Request $p2 0
    if((@($r1.Status,$r2.Status)|Sort-Object)-join ',' -ne '200,201' -or $r1.Json.data.id -ne $r2.Json.data.id){throw 'Concurrent confirm failed idempotency.'}
    $cleanup.Add([pscustomobject]@{Booking=$r1.Json.data.id;Token=$token})
    $path='/api/v1/bookings/'+$r1.Json.data.id+'/cancel'
    $p1=Send-Request POST $path $token $null;$p2=Send-Request POST $path $token $null
    $null=Finish-Request $p1 200;$null=Finish-Request $p2 200

    if($CheckExpiry) {
        $h=(Request POST '/api/v1/concerts/c1/seat-holds' 201 $a.token @{seatIds=@($seat1)}).Json.data
        $cleanup.Add([pscustomobject]@{Hold=$h.holdId;Token=$a.token})
        $wait=([DateTimeOffset]::Parse($h.expiresAt)-[DateTimeOffset]::UtcNow).TotalMilliseconds+250
        if($wait -gt 20000){throw 'CheckExpiry requires a test server with HOLD_DURATION=PT10S or shorter.'}
        if($wait -gt 0){Start-Sleep -Milliseconds ([int][Math]::Ceiling($wait))}
        $null=Request POST '/api/v1/bookings' 410 $a.token @{holdId=$h.holdId;paymentIdentifier='test-pay-shared-01'}
        $released=(Request POST '/api/v1/concerts/c1/seat-holds' 201 $b.token @{seatIds=@($seat1)}).Json.data
        $cleanup.Add([pscustomobject]@{Hold=$released.holdId;Token=$b.token})
        $null=Request DELETE ('/api/v1/seat-holds/'+$released.holdId) 200 $b.token $null
    }
    $results|Format-Table -AutoSize
    $dir=Join-Path (Split-Path $PSScriptRoot) 'build/verification'
    $null=New-Item -ItemType Directory -Force $dir
    $report=[pscustomobject]@{checkedAt=[DateTime]::UtcNow.ToString('o');baseUrl=$BaseUrl;checks=$results.ToArray();expiryChecked=[bool]$CheckExpiry;bookingImplemented=$true}
    [IO.File]::WriteAllText((Join-Path $dir 'booking-http-smoke.json'),($report|ConvertTo-Json -Depth 8),(New-Object Text.UTF8Encoding($false)))
    Write-Output ('Booking HTTP checks passed: '+$results.Count)
} finally {
    # Cancel/release only IDs created by this run, using their own bearer tokens.
    foreach($item in $cleanup) {
        try{
            if($item.PSObject.Properties['Booking']){$null=Request POST ('/api/v1/bookings/'+$item.Booking+'/cancel') 200 $item.Token $null}
            elseif($item.PSObject.Properties['Hold']){$null=Request DELETE ('/api/v1/seat-holds/'+$item.Hold) 200 $item.Token $null}
        }catch{Write-Warning 'Test-owned cleanup needs inspection; details withheld.'}
    }
    $client.Dispose()
}
