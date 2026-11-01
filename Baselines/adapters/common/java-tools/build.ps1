$ErrorActionPreference = "Stop"

Push-Location -LiteralPath $PSScriptRoot
try {
    mvn -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "Maven package failed: $LASTEXITCODE" }

    mvn dependency:build-classpath `
        "-Dmdep.includeScope=runtime" `
        "-Dmdep.outputFile=target/runtime-classpath.txt"
    if ($LASTEXITCODE -ne 0) { throw "Classpath generation failed: $LASTEXITCODE" }
}
finally {
    Pop-Location
}
