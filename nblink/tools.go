//go:build tools

package nblink

// gomobile needs this package in go.mod to make the Android binding.
import _ "golang.org/x/mobile/bind"
