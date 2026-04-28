param(
  [string]$ControllerBaseUrl = "http://localhost:19080",
  [string]$Username = "admin",
  [string]$Password = "admin"
)

$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Net.Http | Out-Null

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$scriptsDir = Join-Path $here "scripts"

if (-not (Test-Path $scriptsDir)) {
  throw "scripts dir not found: $scriptsDir"
}

$pair = "$Username`:$Password"
$b64 = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
$headers = @{ Authorization = "Basic $b64" }

$files = Get-ChildItem -Path $scriptsDir -Filter "*.groovy" | Sort-Object Name
if ($files.Count -eq 0) {
  throw "No .groovy files found in $scriptsDir"
}

Write-Host "Uploading $($files.Count) scripts to $ControllerBaseUrl ..."

foreach ($f in $files) {
  $uploadUrl = "$ControllerBaseUrl/script/api/upload/$($f.Name)"
  Write-Host "- $($f.Name)"
  # nGrinder expects multipart form fields: description, uploadFile
  $client = [System.Net.Http.HttpClient]::new()
  try {
    $client.DefaultRequestHeaders.Authorization =
      [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Basic", $b64)

    $content = [System.Net.Http.MultipartFormDataContent]::new()
    $content.Add([System.Net.Http.StringContent]::new("ticketing load-test script"), "description")

    $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
    $fileContent = [System.Net.Http.ByteArrayContent]::new($bytes)
    $fileContent.Headers.ContentType =
      [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/octet-stream")
    $content.Add($fileContent, "uploadFile", $f.Name)

    $resp = $client.PostAsync($uploadUrl, $content).GetAwaiter().GetResult()
    if (-not $resp.IsSuccessStatusCode) {
      $body = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
      throw "Upload failed: $($resp.StatusCode) $body"
    }
  } finally {
    if ($client) { $client.Dispose() }
  }
}

Write-Host "Done. Verify with: $ControllerBaseUrl/perftest/api/script"

