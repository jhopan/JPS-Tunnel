# JPS Tunnel Desktop

Gio desktop client for JPS Tunnel core.

## Windows bundle

Place these files in one folder:

```text
JPS-Tunnel-Desktop.exe
JPS-Tunnel-Core-windows-amd64.exe
```

The app saves profile and generated runtime config under the OS user config directory. It starts no tunnel until CONNECT is pressed.

## Build

```bash
go vet ./...
go build -ldflags="-s -w -H windowsgui" -o JPS-Tunnel-Desktop.exe .
```
