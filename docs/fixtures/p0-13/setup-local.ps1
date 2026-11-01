# Disposable loopback-only rehearsal. Never use these credentials or certificate publicly.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/../../..").Path
$out = Join-Path $repo 'output/p0-13'
$tls = Join-Path $out 'tls'
New-Item -ItemType Directory -Force -Path $tls | Out-Null
if (!(Test-Path "$tls/fullchain.pem") -or !(Test-Path "$tls/privkey.pem")) {
    # Windows OpenSSL distributions may not ship their compiled-in config path.
    "[req]`ndistinguished_name=dn`n[dn]`n" | Set-Content "$tls/openssl.cnf" -Encoding ascii
    & openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 7 `
        -config "$tls/openssl.cnf" `
        -keyout "$tls/privkey.pem" -out "$tls/fullchain.pem" -subj '/CN=localhost' `
        -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1'
    if ($LASTEXITCODE -ne 0) { throw 'Local TLS certificate generation failed' }
}
$directory = $tls.Replace('\', '/')
@'
POSTGRES_DB=flashcards_acceptance
POSTGRES_USER=flashcards_acceptance
POSTGRES_PASSWORD=p013-local-database-only
ACCOUNT_USERNAME=p013-local
ACCOUNT_PASSWORD_HASH='$2b$10$bmZ1VX907I3mUazhP8TJbOdol3mq3kLib5HaahT4Wm8ElhKzQ35ri'
BACKEND_IMAGE=interview-flashcards-backend:p013-local
WEB_IMAGE=interview-flashcards-web:p013-local
PUBLIC_HOST=localhost
WEB_BIND_ADDRESS=127.0.0.1
HTTP_PORT=8089
HTTPS_PORT=8443
'@ | Set-Content "$out/.env" -Encoding utf8
Add-Content "$out/.env" "TLS_CERT_DIR=$directory" -Encoding utf8
'not a PDF' | Set-Content "$out/invalid.pdf" -Encoding utf8
Write-Output "Prepared $out. Use project name flashcards-p013-acceptance with deploy/compose.production.yml."
