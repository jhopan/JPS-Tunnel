//go:build windows

package main

// No one-time setup needed on Windows — UAC handles privilege escalation
// via the app manifest (requireAdministrator).

func runOneTimeSetup(corePath string) error { return nil }
func resetSetup()                           {}
