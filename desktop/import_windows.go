//go:build windows

package main

import (
	"os/exec"
	"strings"
)

func readClipboard() string {
	out, err := exec.Command("powershell.exe", "-NoProfile", "-Command", "Get-Clipboard -Raw").Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

func chooseImportFile() (string, error) {
	out, err := exec.Command("powershell.exe", "-NoProfile", "-Command", "Add-Type -AssemblyName System.Windows.Forms; $d=New-Object System.Windows.Forms.OpenFileDialog; $d.Filter='JPS/VLESS (*.jps;*.txt)|*.jps;*.txt|All files (*.*)|*.*'; if($d.ShowDialog() -eq 'OK'){$d.FileName}").Output()
	if err != nil || strings.TrimSpace(string(out)) == "" {
		return "", err
	}
	return strings.TrimSpace(string(out)), nil
}
