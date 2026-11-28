# go

Go programs with the toolchain of [go.dev](https://go.dev): build, run and test, also for other systems.

```kotlin
use go

server = build_go("server")
```

```bash
xkit                # downloads Go once, then go build into build/commands/server/server
xkit run            # starts the server
xkit test           # go test ./...
xkit new go hello   # hello/project.build, go.mod, src/main.go and src/main_test.go
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

| Parameter      | Default                                | Meaning                                                                                                          |
|----------------|----------------------------------------|------------------------------------------------------------------------------------------------------------------|
| `name`         | required                               | Name of the module and of the program                                                                            |
| `package`      | `"./src"`                              | The package with `main`, `"."` for sources next to `go.mod`                                                      |
| `directory`    | `"."`                                  | Module directory with `go.mod`, relative to the script                                                           |
| `version`      | `go_version`, `"1.27.4"`               | The Go that starts the build, see [Which Go builds](#which-go-builds)                                            |
| `toolchain`    | `"auto"`                               | `"auto"`: `go.mod` picks the Go, `"local"`: exactly `version`                                                    |
| `targets`      | `[]`                                   | The targets it is built for, like `list("linux-aarch64", "windows-x86_64")`, see [Other systems](#other-systems) |
| `goos`         | `GOOS`, otherwise that of the target   | The system to build for, for systems without a target of xkit, like `"freebsd"`                                  |
| `goarch`       | `GOARCH`, otherwise that of the target | The processor to build for, like `"riscv64"`                                                                     |
| `flags`        | `[]`                                   | Further arguments of `go build`, like `"-ldflags=-s -w"` or `"-race"`                                            |
| `test_flags`   | `[]`                                   | Further arguments of `go test`, like `"-run", "TestParse"`                                                       |
| `inputs`       | `[]`                                   | Files the program embeds besides its Go sources, like templates for `//go:embed`                                 |
| `environment`  | `[]`                                   | `"NAME=value"` for `go build`, `go test` and `run`, after those of the plugin, so they win                       |
| `dependencies` | `[]`                                   | Modules that build before this one and count as its inputs                                                       |

The plugin also defines `go_version`, the default version, and `go_tool(version)`, the path of the `go` command of a version, for scripts that run `go` with `build_command` themselves, like `go generate`.

## How it builds

- `go build -trimpath -o build/commands/<name>/<name> <package>` in the module directory, for another target into `build/<target>/commands/<name>/`. For Windows the program ends in `.exe`.
- It builds again when a `.go` file below the module directory, `go.mod`, `go.sum`, a file of `inputs`, a module of `dependencies` or one of `GOOS`, `GOARCH`, `CGO_ENABLED` and `GOEXPERIMENT` of the shell changed.
- `xkit run` starts the program in the module directory, arguments after `--` go to it.
- `xkit test` runs `go test ./...` in the module directory. The test passes or fails by the exit code of `go test`, the summary has no count of the single tests.

## Which Go builds

`version` is the Go that starts the build, downloaded once from dl.google.com and checked against the SHA-256 published next to it. `project.lock` keeps that SHA-256.

With `toolchain = "auto"`, `GOTOOLCHAIN=auto`, the `go` and `toolchain` lines of `go.mod` decide like when `go` runs on its own: a `go.mod` asking for a newer Go makes `go` download it into its module cache. With `toolchain = "local"`, exactly `version` builds, and a `go.mod` asking for a newer one is an error.

## Other systems

```kotlin
// build/linux-aarch64/commands/server/server and build/windows-x86_64/commands/server/server.exe
build_go("server", targets = list("linux-aarch64", "windows-x86_64"))
```

`targets` takes the targets of xkit, see [cpp.md](https://github.com/xkit-build/xkit/blob/main/docs/cpp.md#other-targets), and builds on every machine. `GOOS` and `GOARCH` follow from the target: `linux-aarch64` is `linux` and `arm64`, `android-armv7` is `android` and `arm` with `GOARM=7`. `host_target()` among them builds for this machine too. `xkit --target linux-aarch64` builds for that target instead of those the script names.

- `xkit run --target <name>` starts the program of that target, through the runner `runner.<target>` names unless this machine runs it itself, see [cpp.md](https://github.com/xkit-build/xkit/blob/main/docs/cpp.md#programs-of-other-targets).
- `xkit test --target <name>` runs `go test` on this machine, which starts the tests itself: that works for targets this machine runs, like `macos-x86_64` on Apple silicon.
- `goos` and `goarch` name a system without a target of xkit, like `build_go("tool", goos = "freebsd", goarch = "amd64")`, into `build/commands/tool/`.
- Programs for other systems build without cgo, which Go turns off for them unless `CGO_ENABLED=1` and a C compiler for the target are set.

## Modules and caches

- Go modules are downloaded by `go` itself and checked by `go.sum`. They are not in `project.lock`, and the first build needs network for them.
- The module cache and the build cache are in xkit's cache, shared by all projects: `caches/plugins/go/modules` and `caches/plugins/go/build`. `xkit clean --cache` removes them, `GOFLAGS=-modcacherw` makes the module cache removable on every system.
- A proxy or private modules go through `environment`: `environment = list("GOPROXY=https://proxy.example.com", "GOPRIVATE=example.com/*")`.

## Embedded files

```kotlin
build_go("site", inputs = list("src/templates/**", "src/static/**"))
```

Files that `//go:embed` takes into the program are no Go sources, so they need `inputs` to build the program again when they change.

## Templates

| Template | `xkit new` | Creates                                                                             |
|----------|------------|-------------------------------------------------------------------------------------|
| `app`    | `go`       | a program with a test: `go.mod` with your domain, `src/main.go`, `src/main_test.go` |

## Limits

- One `build_go` builds one program. A library is just a package that programs import: it needs no module of its own.
- `xkit idea` does not set up Go projects, use the Go support of your editor.
- `go generate`, `go vet` and linters are not run, use `build_command` with `go_tool(go_version)`.
