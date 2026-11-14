# Writing a plugin

A plugin is a build script with functions that turn a call in `project.build` into a module. It is made of three things build-script gives every script:

- [`build_command`](https://github.com/build-script/build/blob/main/docs/plugins.md#build_command) runs a program as a module: with declared inputs and outputs, only when they changed, with `run` and `test` for `build run` and `build test`.
- [`tool`](https://github.com/build-script/build/blob/main/docs/plugins.md#tool) downloads the program once per version, checked against its SHA-256 and `project.lock`.
- [The build language](https://github.com/build-script/build/blob/main/docs/language.md): functions with typed parameters and defaults, conditions, lists, strings as types for fixed choices.

## Start in your project

A plugin can start as a file next to `project.build` and move to a repository once it works:

```
my-project/
├── project.build
├── mkdocs.yml
├── tools/
│   └── docs.build
└── docs/
    └── index.md
```

```kotlin
// tools/docs.build: static sites with mkdocs of the system

build_docs = fn(name: str, directory: str = ".", dependencies: list<module> = list()) -> module {
    build_command(
        name,
        list("mkdocs", "build", "--site-dir", "{output}/site"),
        inputs = list("docs", "mkdocs.yml"),
        outputs = list("{output}/site/index.html"),
        directory = directory,
        run = list("mkdocs", "serve"),
        dependencies = dependencies,
    )
}
```

```kotlin
// project.build
use "./tools/docs.build"

build_docs("site")
```

`build` builds the site once and then only when a file in `docs/` or `mkdocs.yml` changed, `build run` serves it. This plugin needs `mkdocs` installed. The next step is to bring the program along with `tool`, like the Go plugin does.

## A plugin, step by step

[go/plugin.build](../go/plugin.build) is the reference. Its parts:

```kotlin
// Go programs with the toolchain of go.dev: build, run and test
```

The first comment line describes the plugin in `build plugins` and in the choice of `build new`.

```kotlin
go_version = "1.27.4"

_go_system = if operating_system() == "macos" { "darwin" } else { operating_system() }
_go_architecture = if architecture() == "aarch64" { "arm64" } else { "amd64" }
```

Public values are defaults that scripts may read or override, `go_version` here. Names starting with `_` stay inside the plugin: helpers and the names the vendor gives this machine.

```kotlin
go_tool = fn(version: str) -> str {
    archive = "go" + version + "." + _go_system + "-" + _go_architecture + if operating_system() == "windows" { ".zip" } else { ".tar.gz" }
    url = "https://dl.google.com/go/" + archive
    home = tool("go", version, url, sha256_url = url + ".sha256")
    home + "/bin/" + executable("go")
}
```

`tool` downloads the archive for this machine once, checks it against the SHA-256 the vendor publishes, and returns its directory. The version is a parameter, so a project can build with another one without a new release of the plugin.

```kotlin
build_go = fn(
    name: str,
    package: str = "./src",
    directory: str = ".",
    version: str = go_version,
    toolchain: "auto" | "local" = "auto",
    goos: str = env("GOOS", _go_system),
    goarch: str = env("GOARCH", _go_architecture),
    flags: list<str> = list(),
    test_flags: list<str> = list(),
    inputs: list<str> = list(),
    environment: list<str> = list(),
    dependencies: list<module> = list(),
) -> module {
```

The function a script calls. Everything but the name has a default, so `build_go("server")` is a whole build. `toolchain` takes only the two strings it lists, a typo is an error that names the nearest one.

```kotlin
    go = go_tool(version)
    program = "{output}/" + name + if goos == "windows" { ".exe" } else { "" }

    build_command(
        name,
        list(go, "build", "-trimpath", "-o", program) + flags + list(package),
        inputs = list("**/*.go", "go.mod", "go.sum") + inputs,
        outputs = list(program),
        environment = list(
            "GOTOOLCHAIN=" + toolchain,
            "GOOS=" + goos,
            "GOARCH=" + goarch,
            "GOMODCACHE=" + cache_directory("go/modules"),
            "GOCACHE=" + cache_directory("go/build"),
            "GOFLAGS=-modcacherw",
        ) + environment,
        environment_inputs = list("GOOS", "GOARCH", "CGO_ENABLED", "GOEXPERIMENT"),
        directory = directory,
        run = list(program),
        test = list(go, "test") + test_flags + list("./..."),
        dependencies = dependencies,
    )
}
```

- `inputs` names every file the compiler reads. build-script runs the command again only when one of them changed, so a missing input means a stale program.
- `outputs` are below `{output}`, which is `build/commands/<name>/` in the module directory and emptied before every run.
- The caches of the tool go to `cache_directory`, not into the project or the home directory: `clean --cache` removes them with the rest of build-script's cache, and they are shared by all projects.
- `environment` comes after the plugin's own variables, so a script can override them. `environment_inputs` are variables of the user's shell that change what the compiler writes: their values count as inputs.
- `run` is what `build run` starts, `test` what `build test` runs.

## Guidelines

- **One function per kind of module**, named `build_<language>`, which returns `module`. `name` comes first, everything else has a default.
- **A `directory` parameter**, passed to `build_command`. `build add` writes `directory = "<name>"` into the call, so a module of the plugin can live next to others.
- **A `dependencies` parameter** of type `list<module>`, passed on, so the module builds after the modules it uses and again when they change.
- **Versions as parameters** with a public default, like `version: str = go_version`. A new version of the language should not need a new release of the plugin.
- **A SHA-256 for every download**, with `sha256` or `sha256_url`. Without one, the first download is trusted and only `project.lock` checks the following ones.
- **Strings as types** for fixed choices, like `toolchain: "auto" | "local"`, instead of `str`.
- **Programs named for the system they run on.** `executable(name)` gives `name.exe` on Windows. For cross builds the name depends on the target, like `goos` above.
- **No shell.** Commands are a program and its arguments: `sh -c` and `cmd /c` do not exist everywhere.
- **`fail("...")`** for what the plugin does not support, like a system the vendor has no download for, with a message that says what to do instead.
- **Files the plugin brings** are found through `plugin_directory`, not through paths relative to the script.
- **JUnit reports** when the test tool can write them: `test = list(tool, "test", "--junit", "{test_reports}/report.xml")` lets `build test` count the tests. Otherwise only the exit code counts.

## Templates

Templates are what `build new <plugin>` and `build add <plugin>` copy:

```
go/
├── plugin.build
└── templates/
    └── app/
        ├── module.build
        ├── go.mod
        └── src/
            ├── main.go
            └── main_test.go
```

```kotlin
// a Go program with a test
build_go("{{build.name}}")
```

- `module.build` holds exactly one call of a function of the plugin, with the placeholders below. Its first comment line describes the template in the choice of `build new`. `new` writes the call into `project.build` after the `use`, `add` adds `directory = "<name>"`.
- Every other file is copied, with the placeholders replaced in its content and its path.
- `app` is the template without a name: `build new go hello`. Others are named after a colon, `build new go:cli hello`. A plugin with only one template needs no `app`.
- Templates named `app` or ending in `-app` are programs: `add` puts them after the libraries in the script.

| Placeholder            | Becomes                                                                                       |
|------------------------|-----------------------------------------------------------------------------------------------|
| `{{build.name}}`       | the name of the project or module, like `my-tool`                                             |
| `{{build.identifier}}` | the name as an identifier, with `_` for every character that is no letter or digit: `my_tool` |
| `{{build.domain}}`     | the user's domain, like `example.com`                                                         |
| `{{build.package}}`    | the reversed domain and the name, like `com.example.my_tool`, or what `--package` says        |

The user is asked for the domain only when a template uses `{{build.domain}}` or `{{build.package}}`. A `{{build.…}}` that is none of them is an error, most likely a typo. The braces of the language itself stay as they are.

## Trying it out

- **As a file:** `use "./path/to/plugin.build"` in a test project loads the plugin as it is on disk, without git.
- **As the official list:** commit the plugin to a clone of this repository, or to a repository laid out like it, and build with `BUILD_SCRIPT_PLUGINS=file:///path/to/the/clone`. Then `use <name>`, `build plugins` and `build new <name>` take your plugin. Only committed changes count, and tags make releases.
- **Errors** name the line of the plugin next to the call in the script.
- **On every system:** a plugin that downloads its compiler has to pick the right archive for macOS, Linux and Windows, on ARM and x86. The scenarios of build-script/regressions run on all of them.

## Publishing

- **In a repository of your own:** `plugin.build` at its root is `use "you/name"`, one in a directory `use "you/plugins/name"`. Releases are tags `v1.2.3`, for a plugin in a directory `name-v1.2.3`.
- **In this repository:** see [Adding a plugin](../README.md#adding-a-plugin-to-this-repository).
- Use semantic versions. Scripts pin a release in `project.lock` and `--refresh` stays within its major version, so breaking changes need a new major version.

## Limits

What plugins cannot do, today:

**The language**
- No loops, no indexing into lists, no maps, no string functions besides `+` and `match`. A plugin cannot look into a list it is given, like searching `environment` for `GOOS`, so such things need parameters of their own.
- A function that calls itself may go at most 64 calls deep.
- Defaults are evaluated once, when the plugin loads: `env(...)` in a default reads the environment of the build, which is what it should do.

**Modules**
- Loading a plugin only defines functions. A `build_command` at the top of a plugin is an error: modules come from the calls in the script.
- One call of `build_command` is one module that runs one command. Steps one after another are several commands that depend on each other.
- A plugin has no view of the release flag of `build --release`. Release builds need a parameter, like `flags`.
- The modules of build-script take from a command what [the table](https://github.com/build-script/build/blob/main/docs/plugins.md#build_command) lists: C and C++ sources and headers, jars, Java and Kotlin sources. Other kinds of outputs only reach other commands.

**Up to date and caching**
- build-script knows only what the command declares. It does not see files a command reads without naming them, and it does not sandbox commands.
- A command runs whole: when one input changed, the command runs again with all of them. Incremental work is the tool's own, in its caches under `cache_directory`, because `{output}` is emptied before every run.
- Tests count per test only from JUnit XML reports in `{test_reports}`, otherwise by their exit code.

**Dependencies and downloads**
- Downloads go through `tool`: one archive per tool and version, zip or tar, with an optional SHA-256. There is no HTTP in the language.
- The libraries of a language are left to its own tools, like Go modules, which `go` downloads and checks with `go.sum`. They are not in `project.lock`, and they may need network on the first build.

**The command line**
- A plugin adds no commands to build-script, and does not change `install`, `idea` or `clean`. `build idea` leaves command modules out.
- `build run` starts the `run` of a command as it is, `build test` its `test`. Running a program built for another system fails like it would without build-script.
- The choice of `build new` lists only the templates of the official plugins. Other plugins are started with `build new someone/repository`.
