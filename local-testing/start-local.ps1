$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$backendPath = Join-Path $repoRoot 'backend'
$frontendPath = Join-Path $repoRoot 'frontend'
$frontendUrl = 'http://localhost:5500/index.html'
$supabaseUrl = 'https://zstoxaqftyswronvdvxz.supabase.co'
$testSupabaseApiKey = ''
$localSettingsPath = Join-Path $PSScriptRoot 'local-settings.ps1'
if (Test-Path $localSettingsPath) {
    . $localSettingsPath
}
$mavenVersion = '3.9.16'
$isWindowsPlatform = $env:OS -eq 'Windows_NT'
$toolsPath = if ($isWindowsPlatform) {
    Join-Path $env:LOCALAPPDATA 'ReservationSystem\tools'
} else {
    Join-Path $env:HOME '.reservation-system/tools'
}
$mavenHome = Join-Path $toolsPath "apache-maven-$mavenVersion"
$mavenExecutable = if ($isWindowsPlatform) { 'mvn.cmd' } else { 'mvn' }
$mavenCommand = Join-Path $mavenHome "bin/$mavenExecutable"
$pythonServerScriptPath = Join-Path ([System.IO.Path]::GetTempPath()) 'reservation-local-http-server.py'
$frontendProcess = $null
$secureApiKey = $null
$apiKey = $null
$apiKeyPointer = [IntPtr]::Zero

function Refresh-Path {
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = "$machinePath;$userPath"
}

function Install-WinGetPackage([string] $PackageId) {
    Write-Host "Checking or installing $PackageId..."
    & winget install --id $PackageId --exact --source winget --accept-source-agreements --accept-package-agreements
    $installExitCode = $LASTEXITCODE
    Refresh-Path
    if ($installExitCode -ne 0) {
        Write-Warning "WinGet returned exit code $installExitCode for $PackageId. Checking whether the required tool is already installed."
    }
}

function Test-PortInUse([int] $Port) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    try {
        $listener.Start()
        return $false
    } catch {
        return $true
    } finally {
        $listener.Stop()
    }
}

try {
    if (-not (Test-Path $backendPath) -or -not (Test-Path $frontendPath)) {
        throw 'Run this script from a copy of the reservation system repository.'
    }

    if ($isWindowsPlatform) {
        if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
            throw 'Windows Package Manager is missing. Install App Installer from Microsoft Store, restart PowerShell, then run this script again.'
        }

        $java = Get-Command java -ErrorAction SilentlyContinue
        $javac = Get-Command javac -ErrorAction SilentlyContinue
        $javaMajor = 0
        if ($java) {
            $versionOutput = (& $env:ComSpec /d /c 'java -version 2>&1' | Out-String)
            if ($versionOutput -match 'version "(?<major>\d+)') {
                $javaMajor = [int]$Matches.major
            }
        }
        if (-not $java -or -not $javac -or $javaMajor -lt 17) {
            Install-WinGetPackage 'EclipseAdoptium.Temurin.17.JDK'
            $java = Get-Command java -ErrorAction SilentlyContinue
            $javac = Get-Command javac -ErrorAction SilentlyContinue
            if (-not $java -or -not $javac -or $javaMajor -lt 17) {
                $jdkRoots = @(
                    (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
                    (Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium')
                ) | Where-Object { Test-Path $_ }
                $installedJdk = Get-ChildItem -Path $jdkRoots -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
                    Sort-Object Name -Descending |
                    Select-Object -First 1
                if ($installedJdk) {
                    $env:Path = "$(Join-Path $installedJdk.FullName 'bin');$env:Path"
                    $java = Get-Command java -ErrorAction SilentlyContinue
                    $javac = Get-Command javac -ErrorAction SilentlyContinue
                }
            }
            $versionOutput = if ($java) { (& $env:ComSpec /d /c 'java -version 2>&1' | Out-String) } else { '' }
            $javaMajor = if ($versionOutput -match 'version "(?<major>\d+)') { [int]$Matches.major } else { 0 }
            if (-not $java -or -not $javac -or $javaMajor -lt 17) {
                throw 'The Java installer completed, but java and javac are not available yet. Restart PowerShell and run this script again.'
            }
        }

        $javaBinPath = Split-Path $javac.Source -Parent
        $jdkHome = Split-Path $javaBinPath -Parent
        if (-not (Test-Path (Join-Path $jdkHome 'bin\java.exe'))) {
            throw "Could not locate the JDK from javac at $($javac.Source)."
        }
        $env:JAVA_HOME = $jdkHome
        $env:Path = "$javaBinPath;$env:Path"
    } else {
        $java = Get-Command java -ErrorAction SilentlyContinue
        $javac = Get-Command javac -ErrorAction SilentlyContinue
        $javaMajor = 0
        if ($java) {
            $versionOutput = (& $java.Source -version 2>&1 | Out-String)
            if ($versionOutput -match 'version "(?<major>\d+)') {
                $javaMajor = [int]$Matches.major
            }
        }
        if (-not $java -or -not $javac -or $javaMajor -lt 17) {
            $brew = Get-Command brew -ErrorAction SilentlyContinue
            if (-not $brew) {
                throw 'Java 17 and Homebrew are required. Install Homebrew, then run: brew install openjdk@17'
            }
            Write-Host 'Checking or installing openjdk@17...'
            & $brew.Source install openjdk@17
            if ($LASTEXITCODE -ne 0) {
                throw 'Homebrew could not install openjdk@17.'
            }
        }

        $javaHomeVersion = if ($javaMajor -ge 17) { $javaMajor } else { 17 }
        $jdkHome = (& /usr/libexec/java_home -v $javaHomeVersion 2>$null | Out-String).Trim()
        if ([string]::IsNullOrWhiteSpace($jdkHome) -and $javaHomeVersion -eq 17 -and $brew) {
            $jdkHome = (& $brew.Source --prefix openjdk@17 | Out-String).Trim()
        }
        if ([string]::IsNullOrWhiteSpace($jdkHome)) {
            throw 'Java 17 or newer is installed but macOS could not locate its JDK home.'
        }
        $javaBinPath = Join-Path $jdkHome 'bin'
        if (-not (Test-Path (Join-Path $javaBinPath 'java')) -or -not (Test-Path (Join-Path $javaBinPath 'javac'))) {
            throw "Could not locate Java and javac in $javaBinPath."
        }
        $env:JAVA_HOME = $jdkHome
        $env:Path = "$javaBinPath$([System.IO.Path]::PathSeparator)$env:Path"
    }

    if ($isWindowsPlatform) {
        $pythonLauncher = Get-Command py -ErrorAction SilentlyContinue
        $python = Get-Command python -ErrorAction SilentlyContinue
        $pythonUsable = $false
        if ($pythonLauncher) {
            & $pythonLauncher.Source -3 --version *> $null
            $pythonUsable = $LASTEXITCODE -eq 0
        }
        if (-not $pythonUsable -and $python) {
            & $python.Source --version *> $null
            $pythonUsable = $LASTEXITCODE -eq 0
        }
        if (-not $pythonUsable) {
            Install-WinGetPackage 'Python.Python.3.12'
            $pythonLauncher = Get-Command py -ErrorAction SilentlyContinue
            $python = Get-Command python -ErrorAction SilentlyContinue
            if ($pythonLauncher) {
                & $pythonLauncher.Source -3 --version *> $null
                $pythonUsable = $LASTEXITCODE -eq 0
            } elseif ($python) {
                & $python.Source --version *> $null
                $pythonUsable = $LASTEXITCODE -eq 0
            }
            if (-not $pythonUsable) {
                throw 'The Python installer completed, but Python is not available yet. Restart PowerShell and run this script again.'
            }
        }
        $pythonCommand = if ($pythonLauncher) {
            (& $pythonLauncher.Source -3 -c 'import sys; print(sys.executable)' | Out-String).Trim()
        } else {
            $python.Source
        }
    } else {
        $pythonCommand = Get-Command python3 -ErrorAction SilentlyContinue
        if (-not $pythonCommand) {
            $brew = Get-Command brew -ErrorAction SilentlyContinue
            if (-not $brew) {
                throw 'Python 3 is required. Install it with Homebrew (brew install python) and run this script again.'
            }
            Write-Host 'Checking or installing Python 3...'
            & $brew.Source install python
            if ($LASTEXITCODE -ne 0) {
                throw 'Homebrew could not install Python 3.'
            }
            $pythonCommand = Get-Command python3 -ErrorAction SilentlyContinue
        }
        if (-not $pythonCommand) {
            throw 'Python 3 is not available yet. Open a new PowerShell window and run this script again.'
        }
        $pythonCommand = $pythonCommand.Source
    }

    if (-not (Test-Path $mavenCommand)) {
        New-Item -ItemType Directory -Path $toolsPath -Force | Out-Null
        $archiveName = "apache-maven-$mavenVersion-bin.zip"
        $archivePath = Join-Path ([System.IO.Path]::GetTempPath()) $archiveName
        $downloadUrl = "https://dlcdn.apache.org/maven/maven-3/$mavenVersion/binaries/$archiveName"
        $checksumUrl = "$downloadUrl.sha512"

        Write-Host "Downloading Apache Maven $mavenVersion..."
        Invoke-WebRequest -Uri $downloadUrl -OutFile $archivePath
        $expectedHash = (Invoke-WebRequest -Uri $checksumUrl).Content.Trim().Split()[0]
        $actualHash = (Get-FileHash -Path $archivePath -Algorithm SHA512).Hash.ToLowerInvariant()
        if ($actualHash -ne $expectedHash.ToLowerInvariant()) {
            Remove-Item -LiteralPath $archivePath -Force
            throw 'The Maven download did not match its published SHA-512 checksum. The archive was removed.'
        }

        Expand-Archive -LiteralPath $archivePath -DestinationPath $toolsPath -Force
        Remove-Item -LiteralPath $archivePath -Force
    }

    $mavenCommand = Join-Path $mavenHome "bin/$mavenExecutable"
    if (-not (Test-Path $mavenCommand)) {
        throw "Maven was not found at $mavenCommand. Remove the incomplete local Maven folder and run this script again."
    }
    if (-not $isWindowsPlatform) {
        & chmod +x $mavenCommand
        if ($LASTEXITCODE -ne 0) {
            throw "Could not make Maven executable at $mavenCommand."
        }
    }

    foreach ($port in @(5500, 8080)) {
        if (Test-PortInUse $port) {
            throw "Port $port is already in use. Stop the existing service, then run this script again."
        }
    }

    if ([string]::IsNullOrWhiteSpace($testSupabaseApiKey)) {
        Write-Host ''
        Write-Host 'Enter the server-only API key from the reservation-system-test project.'
        Write-Host 'The key is kept in memory for this run and is not written to a file.'
        $secureApiKey = Read-Host 'Test project API key' -AsSecureString
        $apiKeyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureApiKey)
        $apiKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($apiKeyPointer)
    } else {
        $apiKey = $testSupabaseApiKey
    }
    if ([string]::IsNullOrWhiteSpace($apiKey)) {
        throw 'The test project API key cannot be empty.'
    }

    $env:SUPABASE_URL = $supabaseUrl
    $env:APP_CORS_ALLOWED_ORIGIN_PATTERNS = 'http://localhost:5500'
    $env:SERVER_PORT = '8080'
    $env:AUTH_COOKIE_SECURE = 'false'
    $env:AUTH_COOKIE_SAME_SITE = 'Lax'
    $env:AUTH_SESSION_HOURS = '8'

    if (-not (Test-Path $pythonCommand)) {
        throw 'Could not locate the Python executable for the frontend server.'
    }

$pythonServerScript = @'
import http.client
import http.server
import os
from urllib.parse import urlsplit

class LocalHandler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".js": "text/javascript",
        ".mjs": "text/javascript",
    }

    def _proxy_api(self):
        request_body = None
        content_length = self.headers.get("Content-Length")
        if content_length:
            request_body = self.rfile.read(int(content_length))

        parsed_path = urlsplit(self.path)
        connection = http.client.HTTPConnection("localhost", 8080, timeout=60)
        try:
            forwarded_headers = {
                name: value
                for name, value in self.headers.items()
                if name.lower() not in {
                    "host", "connection", "proxy-connection", "keep-alive",
                    "transfer-encoding", "te", "trailers", "upgrade",
                }
            }
            connection.request(
                self.command,
                parsed_path.path + ("?" + parsed_path.query if parsed_path.query else ""),
                body=request_body,
                headers=forwarded_headers,
            )
            response = connection.getresponse()
            response_body = response.read()
            self.send_response(response.status, response.reason)
            for name, value in response.getheaders():
                if name.lower() not in {
                    "connection", "proxy-connection", "keep-alive",
                    "transfer-encoding", "te", "trailers", "upgrade",
                }:
                    self.send_header(name, value)
            if not any(name.lower() == "content-length" for name, _ in response.getheaders()):
                self.send_header("Content-Length", str(len(response_body)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(response_body)
        except (OSError, http.client.HTTPException) as error:
            self.send_error(502, "Local backend is unavailable")
        finally:
            connection.close()

    def do_GET(self):
        if self.path == "/api" or self.path.startswith("/api/"):
            self._proxy_api()
        else:
            super().do_GET()

    def do_HEAD(self):
        if self.path == "/api" or self.path.startswith("/api/"):
            self._proxy_api()
        else:
            super().do_HEAD()

    def do_POST(self):
        self._proxy_api() if self.path == "/api" or self.path.startswith("/api/") else self.send_error(404)

    def do_PUT(self):
        self._proxy_api() if self.path == "/api" or self.path.startswith("/api/") else self.send_error(404)

    def do_PATCH(self):
        self._proxy_api() if self.path == "/api" or self.path.startswith("/api/") else self.send_error(404)

    def do_DELETE(self):
        self._proxy_api() if self.path == "/api" or self.path.startswith("/api/") else self.send_error(404)

    def do_OPTIONS(self):
        self._proxy_api() if self.path == "/api" or self.path.startswith("/api/") else self.send_error(404)

os.chdir(os.environ["RESERVATION_LOCAL_FRONTEND_PATH"])
server = http.server.ThreadingHTTPServer(("localhost", 5500), LocalHandler)
server.serve_forever()
'@
    [System.IO.File]::WriteAllText($pythonServerScriptPath, $pythonServerScript, [System.Text.UTF8Encoding]::new($false))
    $env:RESERVATION_LOCAL_FRONTEND_PATH = $frontendPath
    # The frontend server and browser do not need the Supabase server key.
    $env:SUPABASE_API_KEY = $null
    $quotedPythonServerScriptPath = '"' + $pythonServerScriptPath + '"'
    $frontendProcess = Start-Process -FilePath $pythonCommand -ArgumentList $quotedPythonServerScriptPath -PassThru
    Start-Sleep -Seconds 1
    if ($frontendProcess.HasExited) {
        throw 'The frontend server stopped during startup. Check the Python window for its error.'
    }

    Start-Process $frontendUrl
    Write-Host ''
    Write-Host "Frontend: $frontendUrl"
    Write-Host 'Backend:  http://localhost:8080/api/health'
    Write-Host 'The first backend startup downloads Maven dependencies and can take a few minutes.'
    Write-Host 'Keep this window open while testing. Press Ctrl+C here to stop the backend.'
    Write-Host ''

    # Expose the key only to the backend process started by Maven.
    $env:SUPABASE_API_KEY = $apiKey
    Push-Location $backendPath
    try {
        & $mavenCommand spring-boot:run
        if ($LASTEXITCODE -ne 0) {
            throw "The backend stopped with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
} finally {
    $env:SUPABASE_API_KEY = $null
    $env:RESERVATION_LOCAL_FRONTEND_PATH = $null
    $apiKey = $null
    if ($apiKeyPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($apiKeyPointer)
    }
    if ($secureApiKey) {
        $secureApiKey.Dispose()
    }
    if ($frontendProcess -and -not $frontendProcess.HasExited) {
        Stop-Process -Id $frontendProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path $pythonServerScriptPath) {
        Remove-Item -LiteralPath $pythonServerScriptPath -Force -ErrorAction SilentlyContinue
    }
}
