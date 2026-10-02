//go:build darwin

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

// runOneTimeSetup runs once on macOS: sets the setuid bit on core binary so it
// always runs as root regardless of who launches it.
// Uses osascript "with administrator privileges" → native macOS password dialog.
// Re-runs automatically if core binary is newer than the setup flag (upgrade).
func runOneTimeSetup(corePath string) error {
	flag := setupFlagPath()

	needSetup := false
	flagInfo, err := os.Stat(flag)
	if err != nil {
		needSetup = true
	} else {
		coreInfo, err := os.Stat(corePath)
		if err == nil && coreInfo.ModTime().After(flagInfo.ModTime()) {
			needSetup = true
			_ = os.Remove(flag)
		}
	}
	if !needSetup {
		return nil
	}

	if _, err := os.Stat(corePath); err != nil {
		return nil // core not present yet, skip
	}

	// chmod +s (setuid) on core: causes it to always run as root owner.
	// We chown root first, then set setuid bit.
	// All via osascript → native macOS "JPS Tunnel wants to make changes" dialog.
	script := `do shell script "chown root ` + corePath + ` && chmod +s ` + corePath + `" with administrator privileges`
	cmd := exec.Command("osascript", "-e", script)
	if err := cmd.Run(); err != nil {
		return err
	}

	_ = os.MkdirAll(filepath.Dir(flag), 0700)
	_ = os.WriteFile(flag, []byte("done"), 0600)
	return nil
}

func resetSetup() {
	_ = os.Remove(setupFlagPath())
}
