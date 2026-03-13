# Contributing to Throttle

Thank you for your interest in contributing to Throttle! This document provides guidelines and instructions for contributing.

## How to Contribute

### Reporting Issues

- Use the GitHub issue tracker to report bugs or suggest features
- Before creating a new issue, please search existing issues to avoid duplicates
- Provide as much detail as possible:
  - Steps to reproduce the issue
  - Expected vs actual behavior
  - Java version and environment details
  - Relevant code snippets or logs

### Pull Requests

1. **Fork the repository** and create your branch from `main`
2. **Make your changes**:
   - Follow the existing code style
   - Add tests for new functionality
   - Update documentation as needed
3. **Test your changes**:
   - Run `mvn test` to ensure all tests pass
   - Add new tests for bug fixes or new features
4. **Commit your changes**:
   - Write clear, descriptive commit messages
   - Reference related issues in your commits (e.g., "Fixes #123")
5. **Submit a pull request**:
   - Provide a clear description of the changes
   - Link to related issues
   - Update the CHANGELOG if applicable

## Code Style

- Follow standard Java conventions
- Use meaningful variable and method names
- Add JavaDoc comments for public APIs
- Keep methods focused and reasonably sized
- Write self-documenting code where possible

## Testing

- Write unit tests for new functionality
- Ensure all existing tests pass
- Aim for good test coverage of critical paths
- Test edge cases and error conditions

## Development Setup

1. **Prerequisites**:
   - Java 17 or higher
   - Maven 3.6 or higher

2. **Build the project**:
   ```bash
   mvn clean install
   ```

3. **Run tests**:
   ```bash
   mvn test
   ```

4. **Run the simulator** (optional):
   ```bash
   cd simulator
   mvn spring-boot:run
   ```

## Project Structure

```
throttle/
├── src/main/java/io/github/throttle/
│   ├── service/
│   │   ├── api/          # Public API interfaces
│   │   ├── base/         # Base classes for tasks
│   │   ├── config/       # Configuration classes
│   │   ├── core/         # Core implementation (internal)
│   │   ├── factory/      # Factory classes
│   │   └── monitor/      # Resource monitoring
│   └── module-info.java
├── src/test/java/        # Unit and integration tests
└── simulator/            # Demo and testing application
```

## Documentation

- Update README.md for user-facing changes
- Add JavaDoc for public APIs
- Update the simulator README for simulator changes
- Keep documentation clear and concise

## License

By contributing to Throttle, you agree that your contributions will be licensed under the Apache License 2.0.

## Questions?

Feel free to open an issue for questions or discussions about contributing.

## Code of Conduct

- Be respectful and inclusive
- Focus on constructive feedback
- Help create a welcoming environment for all contributors
