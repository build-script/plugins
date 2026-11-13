package main

import "testing"

func TestGreeting(t *testing.T) {
	if got := greeting("{{build.name}}"); got != "Hello from {{build.name}}" {
		t.Errorf("greeting(\"{{build.name}}\") = %q", got)
	}
}
