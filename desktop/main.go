package main

import (
	"encoding/json"
	"fmt"
	"image/color"
	"os"
	"os/exec"
	"path/filepath"
	"runtime/debug"
	"strings"
	"sync"

	"gioui.org/app"
	"gioui.org/font/gofont"
	"gioui.org/layout"
	"gioui.org/op"
	"gioui.org/op/paint"
	"gioui.org/text"
	"gioui.org/unit"
	"gioui.org/widget"
	"gioui.org/widget/material"
)

const (
	appName = "JPS Tunnel"
	version = "0.1.0"
)

type settings struct {
	Name    string `json:"name"`
	Address string `json:"address"`
	Port    string `json:"port"`
	UUID    string `json:"uuid"`
	Path    string `json:"path"`
	SNI     string `json:"sni"`
	Host    string `json:"host"`
}

type ui struct {
	name, address, port, uuid, path, sni, host widget.Editor
	connect                                    widget.Clickable
	status                                     string
	cmd                                        *exec.Cmd
	mu                                         sync.Mutex
}

func main() {
	if os.Getenv("GOMEMLIMIT") == "" {
		debug.SetMemoryLimit(32 << 20)
	}
	debug.SetGCPercent(50)
	go run()
	app.Main()
}

func run() {
	w := new(app.Window)
	w.Option(app.Title(appName), app.Size(unit.Dp(430), unit.Dp(650)), app.MinSize(unit.Dp(430), unit.Dp(650)))
	var ops op.Ops
	u := newUI(loadSettings())
	for {
		switch e := w.Event().(type) {
		case app.DestroyEvent:
			u.stop()
			return
		case app.FrameEvent:
			gtx := app.NewContext(&ops, e)
			u.layout(gtx)
			e.Frame(gtx.Ops)
		}
	}
}

func newUI(s settings) *ui {
	u := &ui{status: "Disconnected"}
	for _, ed := range []*widget.Editor{&u.name, &u.address, &u.port, &u.uuid, &u.path, &u.sni, &u.host} {
		ed.SingleLine = true
		ed.Submit = true
	}
	u.name.SetText(s.Name)
	u.address.SetText(s.Address)
	u.port.SetText(s.Port)
	u.uuid.SetText(s.UUID)
	u.path.SetText(s.Path)
	u.sni.SetText(s.SNI)
	u.host.SetText(s.Host)
	return u
}

func (u *ui) layout(gtx layout.Context) layout.Dimensions {
	th := material.NewTheme()
	th.Shaper = textShaper()
	th.Palette = material.Palette{
		Bg:         color.NRGBA{R: 0x12, G: 0x14, B: 0x18, A: 0xff},
		Fg:         color.NRGBA{R: 0xf3, G: 0xf5, B: 0xf7, A: 0xff},
		ContrastBg: color.NRGBA{R: 0x1f, G: 0xb8, B: 0x68, A: 0xff},
		ContrastFg: color.NRGBA{R: 0xff, G: 0xff, B: 0xff, A: 0xff},
	}
	paint.Fill(gtx.Ops, th.Palette.Bg)

	inset := layout.UniformInset(unit.Dp(20))
	return inset.Layout(gtx, func(gtx layout.Context) layout.Dimensions {
		return layout.Flex{Axis: layout.Vertical}.Layout(gtx,
			layout.Rigid(material.H4(th, appName+" Desktop").Layout),
			layout.Rigid(layout.Spacer{Height: unit.Dp(2)}.Layout),
			layout.Rigid(material.Label(th, unit.Sp(13), "v"+version+" • By JhopanStore").Layout),
			layout.Rigid(layout.Spacer{Height: unit.Dp(18)}.Layout),
			layout.Rigid(field(th, &u.name, "Nama profil").Layout),
			layout.Rigid(field(th, &u.address, "Address / server").Layout),
			layout.Rigid(field(th, &u.port, "Port").Layout),
			layout.Rigid(field(th, &u.uuid, "UUID").Layout),
			layout.Rigid(field(th, &u.path, "WebSocket path").Layout),
			layout.Rigid(field(th, &u.sni, "SNI").Layout),
			layout.Rigid(field(th, &u.host, "Host header").Layout),
			layout.Rigid(layout.Spacer{Height: unit.Dp(12)}.Layout),
			layout.Rigid(func(gtx layout.Context) layout.Dimensions {
				label := "CONNECT"
				if u.connected() {
					label = "DISCONNECT"
				}
				for u.connect.Clicked(gtx) {
					if u.connected() {
						u.stop()
					} else {
						u.start()
					}
				}
				button := material.Button(th, &u.connect, label)
				button.Background = th.Palette.ContrastBg
				return button.Layout(gtx)
			}),
			layout.Rigid(layout.Spacer{Height: unit.Dp(14)}.Layout),
			layout.Rigid(func(gtx layout.Context) layout.Dimensions {
				label := material.Label(th, unit.Sp(14), u.status)
				if u.connected() {
					label.Color = color.NRGBA{R: 0x4d, G: 0xda, B: 0x8a, A: 0xff}
				} else if strings.HasPrefix(u.status, "GAGAL:") {
					label.Color = color.NRGBA{R: 0xff, G: 0x72, B: 0x72, A: 0xff}
				}
				return label.Layout(gtx)
			}),
		)
	})
}

type fieldWidget struct {
	th    *material.Theme
	ed    *widget.Editor
	label string
}

func field(th *material.Theme, ed *widget.Editor, label string) fieldWidget {
	return fieldWidget{th: th, ed: ed, label: label}
}

func (f fieldWidget) Layout(gtx layout.Context) layout.Dimensions {
	return layout.Inset{Bottom: unit.Dp(7)}.Layout(gtx, material.Editor(f.th, f.ed, f.label).Layout)
}

func textShaper() *text.Shaper { return text.NewShaper(text.WithCollection(gofont.Collection())) }

func (u *ui) values() settings {
	return settings{
		Name: strings.TrimSpace(u.name.Text()), Address: strings.TrimSpace(u.address.Text()),
		Port: strings.TrimSpace(u.port.Text()), UUID: strings.TrimSpace(u.uuid.Text()),
		Path: strings.TrimSpace(u.path.Text()), SNI: strings.TrimSpace(u.sni.Text()), Host: strings.TrimSpace(u.host.Text()),
	}
}

func (u *ui) connected() bool {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.cmd != nil && u.cmd.Process != nil
}

func (u *ui) start() {
	s := u.values()
	if s.Address == "" || s.Port == "" || s.UUID == "" || s.Path == "" || s.SNI == "" || s.Host == "" {
		u.status = "GAGAL: field VLESS belum lengkap."
		return
	}
	port, err := parsePort(s.Port)
	if err != nil {
		u.status = "GAGAL: port harus 1 sampai 65535."
		return
	}
	core, err := corePath()
	if err != nil {
		u.status = "GAGAL: core Windows tidak ditemukan di folder aplikasi."
		return
	}
	config, err := buildConfig(s, port)
	if err != nil {
		u.status = "GAGAL: config tidak bisa dibuat."
		return
	}
	if err := os.MkdirAll(dataDir(), 0700); err != nil {
		u.status = "GAGAL: folder data tidak bisa dibuat."
		return
	}
	if err := os.WriteFile(filepath.Join(dataDir(), "config.json"), config, 0600); err != nil {
		u.status = "GAGAL: config tidak bisa disimpan."
		return
	}
	saveSettings(s)
	cmd := exec.Command(core, "run", "-c", filepath.Join(dataDir(), "config.json"))
	cmd.Dir = dataDir()
	setNoConsole(cmd)
	if err := cmd.Start(); err != nil {
		u.status = "GAGAL: core gagal start — " + err.Error()
		return
	}
	u.mu.Lock()
	u.cmd = cmd
	u.status = "Connected"
	u.mu.Unlock()
	go func() {
		err := cmd.Wait()
		u.mu.Lock()
		if u.cmd == cmd {
			u.cmd = nil
			if err != nil {
				u.status = "GAGAL: core berhenti — " + err.Error()
			} else {
				u.status = "Disconnected"
			}
		}
		u.mu.Unlock()
	}()
}

func (u *ui) stop() {
	u.mu.Lock()
	cmd := u.cmd
	u.cmd = nil
	u.status = "Disconnected"
	u.mu.Unlock()
	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Kill()
	}
}

func dataDir() string {
	d, err := os.UserConfigDir()
	if err != nil {
		return "."
	}
	return filepath.Join(d, "JPSTunnel")
}

func corePath() (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	path := filepath.Join(filepath.Dir(exe), coreName)
	info, err := os.Stat(path)
	if err != nil || info.Size() < 1_000_000 {
		return "", fmt.Errorf("missing core")
	}
	return path, nil
}

func settingsPath() string { return filepath.Join(dataDir(), "settings.json") }

func loadSettings() settings {
	data, err := os.ReadFile(settingsPath())
	if err != nil {
		return settings{Port: "443", Path: "/vless"}
	}
	var s settings
	if json.Unmarshal(data, &s) != nil {
		return settings{Port: "443", Path: "/vless"}
	}
	return s
}

func saveSettings(s settings) {
	if s.Address == "" {
		return
	}
	_ = os.MkdirAll(dataDir(), 0700)
	data, err := json.Marshal(s)
	if err == nil {
		_ = os.WriteFile(settingsPath(), data, 0600)
	}
}

func parsePort(value string) (int, error) {
	var p int
	if _, err := fmt.Sscanf(value, "%d", &p); err != nil || p < 1 || p > 65535 {
		return 0, fmt.Errorf("invalid port")
	}
	return p, nil
}

func buildConfig(s settings, port int) ([]byte, error) {
	config := map[string]any{
		"log": map[string]any{"level": "warn"},
		"inbounds": []any{map[string]any{
			"type": "tun", "tag": "tun-in", "interface_name": "jps-tun", "address": []string{"172.19.0.1/30"},
			"auto_route": true, "strict_route": true, "stack": "gvisor", "mtu": 1280,
		}},
		"outbounds": []any{
			map[string]any{"type": "vless", "tag": "proxy", "server": s.Address, "server_port": port, "uuid": s.UUID,
				"tls":       map[string]any{"enabled": true, "server_name": s.SNI, "insecure": true},
				"transport": map[string]any{"type": "ws", "path": s.Path, "headers": map[string]string{"Host": s.Host}}},
			map[string]any{"type": "direct", "tag": "direct"},
		},
		"route": map[string]any{"auto_detect_interface": true, "final": "proxy"},
	}
	return json.MarshalIndent(config, "", "  ")
}
