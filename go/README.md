# go

Go programs with the toolchain of [go.dev](https://go.dev): build, run and test, also for other systems.

```kotlin
use go

server = build_go("server")
```

```bash
build                # downloads Go once, then go build into build/commands/server/server
build run            # starts the server
build test           # go test ./...
build new go hello   # hello/project.build, go.mod, src/main.go and src/main_test.go
```

The sources are in `src/`, next to `go.mod`:

```
server/
├── project.build
├── go.mod
├── go.sum
└── src/
    └── main.go
```

## build_go

| Parameter      | Default                            | Meaning                                                                                    |
|----------------|------------------------------------|--------------------------------------------------------------------------------------------|
| `name`         | required                           | Name of the module and of the program                                                      |
| `package`      | `"./src"`                          | The package with `main`, `"."` for sources next to `go.mod`                                |
| `directory`    | `"."`                              | Module directory with `go.mod`, relative to the script                                     |
| `version`      | `go_version`, `"1.27.4"`           | The Go that starts the build, see [Which Go builds](#which-go-builds)                      |
| `toolchain`    | `"auto"`                           | `"auto"`: `go.mod` picks the Go, `"local"`: exactly `version`                              |
| `goos`         | `GOOS`, otherwise this system      | The system to build for, like `"linux"`, `"windows"` or `"darwin"`                         |
| `goarch`       | `GOARCH`, otherwise this processor | The processor to build for, like `"arm64"` or `"amd64"`                                    |
| `flags`        | `[]`                               | Further arguments of `go build`, like `"-ldflags=-s -w"` or `"-race"`                      |
| `test_flags`   | `[]`                               | Further arguments of `go test`, like `"-run", "TestParse"`                                 |
| `inputs`       | `[]`                               | Files the program embeds besides its Go sources, like templates for `//go:embed`           |
| `environment`  | `[]`                               | `"NAME=value"` for `go build`, `go test` and `run`, after those of the plugin, so they win |
| `dependencies` | `[]`                               | Modules that build before this one and count as its inputs                                 |

The plugin also defines `go_version`, the default version, and `go_tool(version)`, the path of the `go` command of a version, for scripts that run `go` with `build_command` themselves, like `go generate`.

## How it builds

- `go build -trimpath -o build/commands/<name>/<name> <package>` in the module directory. On Windows, and for `goos = "windows"`, the program ends in `.exe`.
- It builds again when a `.go` file below the module directory, `go.mod`, `go.sum`, a file of `inputs`, a module of `dependencies` or one of `GOOS`, `GOARCH`, `CGO_ENABLED` and `GOEXPERIMENT` of the shell changed.
- `build run` starts the program in the module directory, arguments after `--` go to it.
- `build test` runs `go test ./...` in the module directory. The test passes or fails by the exit code of `go test`, the summary has no count of the single tests.

## Which Go builds

`version` is the Go that starts the build, downloaded once from dl.google.com and checked against the SHA-256 published next to it. `project.lock` keeps that SHA-256.

With `toolchain = "auto"`, `GOTOOLCHAIN=auto`, the `go` and `toolchain` lines of `go.mod` decide like when `go` runs on its own: a `go.mod` asking for a newer Go makes `go` download it into its module cache. With `toolchain = "local"`, exactly `version` builds, and a `go.mod` asking for a newer one is an error.

## Other systems

```kotlin
build_go("server", goos = "linux", goarch = "arm64")
build_go("tool", goos = "windows", goarch = "amd64")    // build/commands/tool/tool.exe
```

Both build on every machine. `build run` and `build test` of a program for another system fail, like they would without build-script. Such programs build without cgo, which Go turns off for other systems unless `CGO_ENABLED=1` and a C compiler for the target are set.

## Modules and caches

- Go modules are downloaded by `go` itself and checked by `go.sum`. They are not in `project.lock`, and the first build needs network for them.
- The module cache and the build cache are in build-script's cache, shared by all projects: `caches/plugins/go/modules` and `caches/plugins/go/build`. `build clean --cache` removes them, `GOFLAGS=-modcacherw` makes the module cache removable on every system.
- A proxy or private modules go through `environment`: `environment = list("GOPROXY=https://proxy.example.com", "GOPRIVATE=example.com/*")`.

## Embedded files

```kotlin
build_go("site", inputs = list("src/templates/**", "src/static/**"))
```

Files that `//go:embed` takes into the program are no Go sources, so they need `inputs` to build the program again when they change.

## Templates

| Template | `build new` | Creates                                                                             |
|----------|-------------|-------------------------------------------------------------------------------------|
| `app`    | `go`        | a program with a test: `go.mod` with your domain, `src/main.go`, `src/main_test.go` |

## Limits

- One `build_go` builds one program. A library is just a package that programs import: it needs no module of its own.
- `build idea` does not set up Go projects, use the Go support of your editor.
- `go generate`, `go vet` and linters are not run, use `build_command` with `go_tool(go_version)`.
