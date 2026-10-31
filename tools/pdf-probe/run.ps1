param(
    [string]$Pdf = '',
    [switch]$RenderSamples
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $Pdf) {
    $candidates = @(Get-ChildItem -LiteralPath (Join-Path $repo 'docs') -Filter '*.pdf' -File)
    if ($candidates.Count -ne 1) { throw 'Pass -Pdf explicitly when docs does not contain exactly one PDF' }
    $Pdf = $candidates[0].FullName
}
$probeOutput = Join-Path $repo 'output/pdf-probe'
$pom = Join-Path $PSScriptRoot 'pom.xml'
$probeClasspath = (Join-Path $PSScriptRoot 'target/classes') + [IO.Path]::PathSeparator + (Join-Path $PSScriptRoot 'target/lib/*')
& mvn -B -f $pom compile org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies '-DoutputDirectory=target/lib'
if ($LASTEXITCODE -ne 0) { throw 'PDFBox probe build failed' }
& java '-Djava.awt.headless=true' -cp $probeClasspath PdfProbe extract $Pdf (Join-Path $probeOutput 'raw.json')
if ($LASTEXITCODE -ne 0) { throw 'PDF extraction failed' }
& python -X utf8 (Join-Path $PSScriptRoot 'probe.py') (Join-Path $probeOutput 'raw.json') --output $probeOutput
if ($LASTEXITCODE -ne 0) { throw 'PDF validation failed; inspect output/pdf-probe/report.md' }
& python -X utf8 -m unittest discover -s $PSScriptRoot -p test_probe.py -v
if ($LASTEXITCODE -ne 0) { throw 'Regression checks failed' }
if ($RenderSamples) {
    & java '-Djava.awt.headless=true' -cp $probeClasspath PdfProbe render $Pdf (Join-Path $probeOutput 'rendered') '1,10,11,12,26,44,80,89,119,123,150'
    if ($LASTEXITCODE -ne 0) { throw 'Sample rendering failed' }
}
