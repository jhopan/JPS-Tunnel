//go:build !windows

package main

import "os/exec"

func setNoConsole(cmd *exec.Cmd) {}
func setWindowDarkMode()         {}
