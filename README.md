# build-script plugins

The official plugins of [build-script](https://github.com/build-script/build). A plugin brings a language to build-script without changing build-script itself: it is a build script whose functions download the compiler of the language and run it.

| Plugin   | `use`    | Builds                                                           | Templates |
|----------|----------|------------------------------------------------------------------|-----------|
| [go](go) | `use go` | Go programs with the toolchain of go.dev, also for other systems | `app`     |

```kotlin
use go

build_go("server")
```

```bash
build run                # downloads Go once, builds and starts the server
build new go hello       # a new project from the template app of the Go plugin
build plugins            # the plugins of this repository and their templates
```

Writing a plugin of your own is explained in [docs/writing-plugins.md](docs/writing-plugins.md), together with what plugins cannot do.

## How plugins work

### Where build-script finds a plugin

`use` names a plugin in one of three ways:

| `use`                     | Where the plugin is                                                                                                                             |
|---------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| `use go`                  | `go/plugin.build` in this repository, when `go` is no language of build-script itself                                                           |
| `use "someone/zig"`       | `plugin.build` of the GitHub repository `someone/zig`, `"gitlab.com/someone/zig"` of another host, `"someone/plugins/zig"` in a directory of it |
| `use "./tools/zig.build"` | a file of the project, relative to the file with the `use`                                                                                      |

`BUILD_SCRIPT_PLUGINS` replaces this repository with another one, like a mirror in a company or a `file://` repository with changes to try out.

### Which version

Releases are git tags with a version. In this repository they carry the name of the plugin, `go-v1.4.2`, in a repository of its own `v1.4.2`, and for a plugin in a directory `zig-v1.4.2`.

1. `use go` takes the newest release, `use go@1` the newest 1.x, `use go@1.4` the newest 1.4.x. `use go@main` and `use go@<commit>` take a branch or a commit. A repository without releases is taken at its default branch.
2. build-script asks the repository for its tags with `git ls-remote` and fetches only the one commit it chose into its cache, `caches/git/checkouts/<repository>/<tag>-<commit>/`.
3. `project.lock` keeps the tag and the commit: `plugin:go commit=9327d85... ref=go-v1.4.2`. Later builds and other machines take that commit, from the cache and without network once it is there.
4. A tag that points to another commit than the lock says stops the build: somebody moved it. `build --refresh` takes the newest release again, but stays within the major version the lock had, so a project does not get breaking changes by surprise.

### What a plugin sees and defines

A plugin is a build script like `project.build`, and it runs once per build, when the first `use` names it.

- It runs in a scope of its own. It sees the functions every script has, like `list`, `env`, `build_command` and `tool`, the variable `plugin_directory` with the directory of its `plugin.build`, and what it loads itself with `use`, other plugins and the languages of build-script among them. It never sees the variables of the script that uses it.
- The script gets the variables the plugin assigns at its top level, except those starting with `_`, which stay private. Functions keep seeing the variables of the plugin, also the private ones.
- A plugin that two scripts or plugins use is loaded once, so they get the same values. When two different plugins define the same name differently, the build stops. A plugin that uses itself, also through others, is an error.
- Loading a plugin only defines functions. Modules come from the script: when the script calls `build_go("server")`, the function calls `build_command`, and that module builds like every other one, at the same time as the modules it does not depend on, and only when its inputs changed.
- An error inside a plugin names the call in the script and the line in the plugin: `project.build:3:1: build_go: ... (in .../go/plugin.build:57:5)`.

### Templates for new and add

A plugin may bring templates for `build new` and `build add`, in `templates/<name>/` next to its `plugin.build`. `build new go hello` copies `templates/app/`, fills in the name, and writes the call of its `module.build` into `hello/project.build`, after a `use go`. `build new go:cli hello` takes the template `cli`. Templates come from the release `project.lock` keeps, so `add` gives new modules the same release as the others.

Without a kind, `build new` and `build add` offer the templates of the plugins in this repository next to the kinds of build-script, each with the first comment line of its `module.build`.

## Releases of this repository

- A release of a plugin is a tag `<plugin>-v<version>`, like `go-v1.0.0`. As long as a plugin has no release, `use go` takes the default branch.
- A new major version is for changes that break existing scripts, like a renamed parameter. Projects stay on their major version until `@` names a new one.
- Every release has to build the scenarios of [build-script/regressions](https://github.com/build-script/regressions) that use it.

## Adding a plugin to this repository

1. A directory with the name of the plugin, with its `plugin.build`. Its first comment line describes it for `build plugins` and the choice of `build new`.
2. A `README.md` next to it with its functions and their parameters, like [go/README.md](go/README.md).
3. Optionally templates in `templates/<name>/`, see [docs/writing-plugins.md](docs/writing-plugins.md#templates).
4. A scenario in build-script/regressions that builds, runs and tests a project with it on Linux, macOS and Windows.
