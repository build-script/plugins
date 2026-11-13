package main

import "testing"

func TestGreeting(t *testing.T) {
	if got := greeting("{name}"); got != "Hello from {name}" {
		t.Errorf("greeting(\"{name}\") = %q", got)
	}
}
