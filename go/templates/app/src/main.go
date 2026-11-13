package main

import "fmt"

// greeting is what the program says
func greeting(name string) string {
	return "Hello from " + name
}

func main() {
	fmt.Println(greeting("{name}"))
}
