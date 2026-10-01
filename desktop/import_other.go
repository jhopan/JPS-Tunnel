//go:build !windows

package main

import "fmt"

func readClipboard() string { return "" }
func chooseImportFile() (string, error) {
	return "", fmt.Errorf("file picker belum tersedia pada platform ini")
}
