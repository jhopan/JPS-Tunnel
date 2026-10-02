//go:build linux

package main

import (
	"os"
	"os/exec"
	"path/filepath"
)

func setupFlagPath() string {
	d, _ := os.UserConfigDir()
	return filepath.Join(d, "JPSTunnel", ".setup_done")
}

// runOneTimeSetup runs once: sets cap_net_admin+cap_net_raw on the core binary
// so it can create TUN interfaces without root on subsequent runs.
// Uses pkexec (polkit GUI dialog) so no terminal appears.
// Re-runs automatically if core binary is newer than the setup flag (upgrade).
func runOneTimeSetup(corePath string) error {
	flag := setupFlagPath()

	// Check if setup needed: flag missing OR core newer than flag (upgrade)
	needSetup := false
	flagInfo, err := os.Stat(flag)
	if err != nil {
		needSetup = true // flag missing
	} else {
		coreInfo, err := os.Stat(corePath)
		if err == nil && coreInfo.ModTime().After(flagInfo.ModTime()) {
			needSetup = true // core was upgraded
			_ = os.Remove(flag)
		}
	}

	if !needSetup {
		return nil
	}

	// Verify core exists before trying to setcap
	if _, err := os.Stat(corePath); err != nil {
		return nil // core not present yet, skip
	}

	// Try pkexec (Gnome/KDE native GUI password dialog)
	// Falls back to gksudo / kdesudo if pkexec unavailable
	launchers := []string{"pkexec", "gksudo", "kdesudo"}
	var cmd *exec.Cmd
	for _, launcher := range launchers {
		if _, err := exec.LookPath(launcher); err == nil {
			cmd = exec.Command(launcher,
				"setcap", "cap_net_admin,cap_net_raw+ep", corePath)
			break
		}
	}
	if cmd == nil {
		// Last resort: xterm with sudo
		cmd = exec.Command("xterm", "-e",
			"sudo setcap cap_net_admin,cap_net_raw+ep "+corePath)
	}

	if err := cmd.Run(); err != nil {
		return err
	}

	// Save flag so we never prompt again
	_ = os.MkdirAll(filepath.Dir(flag), 0700)
	_ = os.WriteFile(flag, []byte("done"), 0600)
	return nil
}

// resetSetup removes the setup flag — forces re-setup on next launch.
// Called if core binary changes (upgrade).
func resetSetup() {
	_ = os.Remove(setupFlagPath())
}
