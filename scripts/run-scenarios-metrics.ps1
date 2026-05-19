$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'

function Get-BizSnapshot {
  $raw = curl.exe -s "$base/api/dashboard/business-metrics"
  $j = $raw | ConvertFrom-Json
  return @{
    q    = [double]$j.queueEnteredTotal
    a    = [double]$j.admissionIssuedTotal
    sl   = [double]$j.seatLockFailedTotal
    rl   = [double]$j.rateLimitRejectedTotal
    http = [double]$j.httpServerRequestTotal
    ex   = [double]$j.reservationExpiredTotal
    pr   = [double]$j.paymentRequestedTotal
    ps   = [double]$j.paymentSucceededTotal
    pf   = [double]$j.paymentFailedTotal
  }
}

$rows = New-Object System.Collections.ArrayList
$prev = Get-BizSnapshot

function Invoke-Scenario {
  param(
    [string]$Name,
    [int]$SleepSec,
    [string]$Url
  )
  $null = curl.exe -s -X POST $Url
  Start-Sleep -Seconds $SleepSec
  $cur = Get-BizSnapshot
  [void]$rows.Add([pscustomobject]@{
      Scenario           = $Name
      DeltaQueueEntered  = [math]::Round($cur.q - $prev.q, 0)
      DeltaAdmission     = [math]::Round($cur.a - $prev.a, 0)
      DeltaSeatLockFail  = [math]::Round($cur.sl - $prev.sl, 0)
      DeltaRateLimit429  = [math]::Round($cur.rl - $prev.rl, 0)
      DeltaHttpRequests  = [math]::Round($cur.http - $prev.http, 0)
      DeltaResExpired    = [math]::Round($cur.ex - $prev.ex, 0)
      DeltaPayRequested  = [math]::Round($cur.pr - $prev.pr, 0)
      DeltaPaySucceeded  = [math]::Round($cur.ps - $prev.ps, 0)
      DeltaPayFailed     = [math]::Round($cur.pf - $prev.pf, 0)
    })
  $script:prev = $cur
}

Invoke-Scenario 'A' 22 ($base + '/api/dashboard/ngrinder/scenarios/start?scenario=A&vusers=18&threads=18&eventSeatCount=56&testDurationSec=14')
Invoke-Scenario 'B' 38 ($base + '/api/dashboard/ngrinder/scenarios/start?scenario=B&vusers=15&threads=15')
Invoke-Scenario 'C' 22 ($base + '/api/dashboard/ngrinder/scenarios/start?scenario=C&vusers=14&threads=14&eventSeatCount=56&testDurationSec=14')
Invoke-Scenario 'D' 16 ($base + '/api/dashboard/ngrinder/scenarios/start?scenario=D&vusers=8&threads=8&eventSeatCount=40&holdTtlSeconds=60&sleepMs=75000')
Invoke-Scenario 'E' 50 ($base + '/api/dashboard/ngrinder/scenarios/start?scenario=E&eventSeatCount=20&crowdMultiplier=4')
Invoke-Scenario 'F' 42 ($base + '/api/dashboard/ngrinder/scenarios/start?scenario=F&vusers=12&threads=12&eventSeatCount=48&testDurationSec=35')

$rows | ConvertTo-Json -Depth 5
