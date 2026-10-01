//go:build windows

package main

import (
	"os"
	"os/exec"
	"sync"
	"syscall"
	"unsafe"
)

func setNoConsole(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{
		HideWindow:    true,
		CreationFlags: 0x08000000,
	}
}

var (
	user32                       = syscall.NewLazyDLL("user32.dll")
	procEnumWindows              = user32.NewProc("EnumWindows")
	procGetWindowThreadProcessId = user32.NewProc("GetWindowThreadProcessId")
	procIsWindowVisible          = user32.NewProc("IsWindowVisible")
	dwmapi                       = syscall.NewLazyDLL("dwmapi.dll")
	procDwmSetWindowAttribute    = dwmapi.NewProc("DwmSetWindowAttribute")
	darkOnce                     sync.Once
)

func setWindowDarkMode() {
	darkOnce.Do(func() {
		pid := uint32(os.Getpid())
		cb := syscall.NewCallback(func(hwnd uintptr, lparam uintptr) uintptr {
			var wpid uint32
			procGetWindowThreadProcessId.Call(hwnd, uintptr(unsafe.Pointer(&wpid)))
			if wpid == pid {
				r, _, _ := procIsWindowVisible.Call(hwnd)
				if r != 0 {
					dark := int32(1)
					procDwmSetWindowAttribute.Call(hwnd, 20, uintptr(unsafe.Pointer(&dark)), 4)
					procDwmSetWindowAttribute.Call(hwnd, 19, uintptr(unsafe.Pointer(&dark)), 4)
				}
			}
			return 1
		})
		procEnumWindows.Call(cb, 0)
	})
}
