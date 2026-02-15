# EGit Agent Development Guide

This guide is for AI coding agents working on the EGit (Eclipse Git Plugin) codebase.

## Project Overview

EGit is an Eclipse plugin for Git integration, built on JGit. The project uses:
- **Build System**: Maven + Tycho (Eclipse plugin build)
- **Java Version**: Java 21
- **License**: EPL-2.0 (Eclipse Public License 2.0)
- **Architecture**: Eclipse OSGi plugin architecture with multiple bundles

## Build Commands

### Full Build
```bash
mvn clean verify
```

### Build without tests
```bash
mvn clean verify -DskipTests
```

### Static analysis (SpotBugs, PMD)
```bash
mvn clean verify -Pstatic-checks
```

### Run all tests
```bash
mvn clean test
```

### Run a specific test class
```bash
cd org.eclipse.egit.core.test
mvn test -Dtest=GitHubJsonParserTest
```

### Run a single test method
```bash
cd org.eclipse.egit.core.test
mvn test -Dtest=GitHubJsonParserTest#testParseChangedFiles
```

## Project Structure

### Main Bundles
- `org.eclipse.egit.core` - Core Git integration, team provider, API clients
- `org.eclipse.egit.ui` - User interface components, views, wizards
- `org.eclipse.egit.gitflow` - GitFlow branching model support
- `org.eclipse.egit.gitflow.ui` - GitFlow UI components

### Test Bundles
- `org.eclipse.egit.core.test` - Core functionality tests
- `org.eclipse.egit.ui.test` - UI tests (uses SWTBot)
- `org.eclipse.egit.core.junit` - Shared test utilities

### Features & Packaging
- `org.eclipse.egit-feature` - Main feature definition
- `org.eclipse.egit.repository` - P2 repository for distribution
- `org.eclipse.egit.doc` - Documentation bundle

## Code Style Guidelines

### Formatting
- **Indentation**: TABS (width 4)
- **Line length**: 80 characters (comments and code)
- **Braces**: End of line style (K&R)
- **Blank lines**: 1 line between methods, 1 line before fields
- **No trailing whitespace**

### Imports
- **Order**: `java`, `javax`, `org`, `com`
- **No wildcards**: Organize imports with threshold 99 (explicit imports only)
- **No unused imports**: Automatically removed on save
- **Static imports last**

### Naming Conventions
- **Classes**: PascalCase (e.g., `GitHubClient`, `PullRequestChangedFile`)
- **Methods**: camelCase (e.g., `getPullRequestChanges`, `parseChangedFiles`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `API_BASE_URL`, `DEFAULT_TIMEOUT`)
- **Fields**: camelCase with `this.` prefix only when necessary
- **Packages**: lowercase, use `internal` for non-API classes

### Documentation
- **Javadoc required** for all public/protected classes and methods
- **Format**: Standard Javadoc with `@param`, `@return`, `@throws`
- **Visibility**: Private methods require Javadoc at line 58+ in compiler settings
- **License header**: EPL-2.0 required on all source files

### Annotations
- Use `@NonNull` and `@Nullable` from `org.eclipse.jgit.annotations`
- Use `@Override` for all overridden methods
- Use `@SuppressWarnings` sparingly with justification

### Error Handling
- **Use Eclipse logging**: `Activator.logError()`, `Activator.logWarning()`
- **NO System.out/err**: Never use `System.out.println()` or `System.err.println()`
- **NO printStackTrace()**: Use proper logging framework
- **Legitimate error logging only**: Don't log normal conditions (empty results, etc.)
- **Check for null**: Add null checks before operations on potentially null objects

### String Literals
- **Externalize strings**: Use `//$NON-NLS-1$` comment for non-translatable strings
- **Error messages**: Should be externalized for internationalization
- **Example**: `private static final String API_URL = "https://api.github.com"; //$NON-NLS-1$`

### Code Organization
- **Provider-agnostic models**: Model classes in `org.eclipse.egit.core.internal.bitbucket` are shared between providers
- **Provider-specific clients**: GitHub client in `org.eclipse.egit.core.internal.github`
- **No external JSON libs**: Hand-rolled JSON parsing (character-by-character)
- **Stream API**: Use Java Streams for collections when appropriate

### Testing
- **JUnit 4/5**: Tests use JUnit framework
- **Test naming**: `testMethodName` or `testMethodName_Condition`
- **SWTBot for UI**: UI tests use SWTBot framework
- **Mock sparingly**: Prefer real objects when practical

## Common Patterns

### Null Safety
```java
if (file.getType() != null) {
    switch (file.getType()) {
        // handle types
    }
} else {
    // default handling
}
```

### Logging
```java
// Error logging (with exception)
Activator.logError("Failed to fetch PR comments", exception);

// Warning logging
Activator.logWarning("Unexpected JSON format: " + json);

// Info logging (use sparingly, only for significant events)
Activator.logInfo("GitHub client initialized");
```

### String Building
```java
// For API URLs and concatenation
String url = API_BASE_URL + "/repos/" + owner + "/" + repo;

// For complex strings
StringBuilder sb = new StringBuilder();
sb.append("Complex ").append(value);
```

### Resource Management
```java
// Use try-with-resources
try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
    // use reader
}
```

## GitHub API Integration Notes

- **Pagination**: GitHub API returns max 30 items by default, 100 with `per_page=100`
- **Link header**: Use `Link` header with `rel="next"` for pagination
- **Rate limiting**: Be aware of GitHub API rate limits (5000/hour authenticated)
- **No external libraries**: EGit uses hand-rolled HTTP and JSON parsing

## Contributing

1. **Sign ECA**: All contributors must sign Eclipse Contributor Agreement
2. **Use Gerrit**: Changes reviewed on [GerritHub](https://eclipse.gerrithub.io/q/project:eclipse-egit/egit+status:open)
3. **Commit messages**: Follow conventional format, reference bug numbers
4. **No force push**: Never force push to main/master
5. **Test first**: Ensure tests pass before submitting

## Key Files to Reference

- `.settings/org.eclipse.jdt.core.prefs` - Compiler and formatter settings
- `.settings/org.eclipse.jdt.ui.prefs` - UI preferences, import order
- `pom.xml` - Build configuration
- `CONTRIBUTING.md` - Contribution guidelines
- `LICENSE` - EPL-2.0 license

## Tools & Commands

- **Maven**: `mvn` (minimum version 3.9.0)
- **Tycho**: Eclipse/OSGi build plugin (version 4.0.13)
- **Java**: JDK 21 required
- **Encoding**: UTF-8 for all files
