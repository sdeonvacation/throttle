# AGENTS.md - Guidelines for Agentic Coding in Throttle Repository

This file provides guidance to agentic coding agents (like yourself) when working with code in the Throttle repository.

## Build, Lint, and Test Commands

### Building the Project
```bash
# Build the core library
mvn clean install

# Build the simulator
cd simulator && mvn clean install
```

### Running Tests
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=ClassName

# Run specific test method
mvn test -Dtest=ClassName#methodName

# Skip tests (useful for quick builds)
mvn clean install -DskipTests
```

### Simulator Commands
```bash
# Build simulator
cd simulator && mvn clean install

# Run simulator application
cd simulator && mvn spring-boot:run

# Run simulator with increased heap (for memory pressure tests)
cd simulator && mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx4g"

# Run all simulator test scenarios
cd simulator && ./run-tests.sh

# Run specific simulator scenario
cd simulator && ./run-tests.sh 1              # By number
cd simulator && ./run-tests.sh normal         # By alias
cd simulator && ./run-tests.sh flapping       # Edge case scenario
```

## Code Style Guidelines

### Java Version
- Target Java 17 (as specified in pom.xml)
- Use Java 17 language features appropriately

### Imports
- Organize imports in standard order: Java, third-party, project-specific
- Use explicit imports rather than wildcards when possible
- Remove unused imports
- Group related imports together with blank lines between groups

### Formatting
- Use 4 spaces for indentation (not tabs)
- Maximum line length: 120 characters
- Opening braces on same line as declaration
- Closing braces on their own line
- No trailing whitespace
- Empty lines between methods and logical sections

### Naming Conventions
- Classes: PascalCase (e.g., `ThrottleServiceImpl`)
- Methods and variables: camelCase (e.g., `processChunk`, `chunkSize`)
- Constants: UPPER_SNAKE_CASE (e.g., `MAX_PAUSE_COUNT`)
- Interfaces: Adjectives or nouns (e.g., `ChunkableTask`, `ResourceMonitor`)
- Booleans: Prefix with `is`, `has`, `should` (e.g., `isPaused`, `hasCapacity`)
- Exception classes: End with `Exception` (e.g., `TaskTerminatedException`)
- Test classes: Append `Test` (e.g., `ThrottleServiceTest`)
- Test methods: descriptive names using `should` or `given_when_then` pattern

### Types and Generics
- Use generics appropriately for type safety
- Prefer interfaces over implementations in method signatures
- Use diamond operator `<>` when instantiating generic types
- Avoid raw types unless interacting with legacy code

### Error Handling
- Use checked exceptions for recoverable conditions
- Use unchecked exceptions (RuntimeException) for programming errors
- Don't catch exceptions unless you can handle them meaningfully
- Log exceptions at appropriate levels (debug, warn, error)
- Preserve stack traces when wrapping exceptions
- Follow the existing pattern in the codebase for exception handling

### Comments and Documentation
- Use Javadoc for public APIs and protected members
- Keep comments up-to-date when modifying code
- Focus comments on why, not what (unless the what is complex)
- Use TODO comments with assignee and date when appropriate: `// TODO: [username] [date] - description`
- Avoid commented-out code; remove it instead

### Specific Conventions from Throttle Codebase
1. **Task Implementation**:
   - Extend `AbstractChunkableTask<T>` for custom tasks
   - Implement `processChunk()` method for actual work
   - Do NOT call `completeTask()` or `failTask()` from client code
   - Use `onComplete()` and `onError()` for lifecycle callbacks

2. **Resource Monitoring**:
   - Monitors implement `ResourceMonitor` interface
   - `evaluate()` method returns `MonitorState.HOT` or `MonitorState.NORMAL`
   - Handle monitor exceptions gracefully (fail-open)

3. **Configuration**:
   - Use `ThrottleServiceFactory.Builder` for configuration
   - Follow builder pattern conventions
   - Configuration objects are immutable

4. **Threading and Concurrency**:
   - Prefer `ExecutorService` over direct thread management
   - Use proper synchronization when accessing shared state
   - Avoid busy-waiting; use proper waiting mechanisms
   - Follow the thread-task binding pattern: one thread per task

### Logging
- Use SLF4J for logging (`org.slf4j.Logger`)
- Logger declaration: `private static final Logger LOGGER = LoggerFactory.getLogger(ClassName.class);`
- Log at appropriate levels:
  - ERROR: System errors requiring attention
  - WARN: Potentially harmful situations
  - INFO: Important runtime events
  - DEBUG: Detailed information for debugging
  - TRACE: Most detailed information

### Testing
- Write unit tests for all new functionality
- Follow AAA pattern: Arrange, Act, Assert
- Use descriptive test method names
- Test both positive and negative cases
- Mock dependencies appropriately
- Keep tests focused and independent
- Aim for high code coverage but prioritize meaningful tests

### Simulator Specific
- Follow Spring Boot conventions
- REST controllers return appropriate HTTP status codes
- WebSocket endpoints handle connections properly
- Load generators create realistic system load
- Dashboard updates are efficient and non-blocking

## Additional Notes

### Module System
- This is a Java 17+ modular project
- The `core` package is internal and not exported
- Only export packages that are part of the public API
- Follow the module declarations in `module-info.java`

### Performance Considerations
- Minimize object creation in hot paths
- Use primitive types when appropriate
- Avoid unnecessary synchronization
- Consider memory allocation patterns in frequently called methods

### Security
- Validate all inputs
- Avoid logging sensitive information
- Follow principle of least privilege
- Keep dependencies updated

When in doubt, follow the existing patterns in the codebase and refer to the detailed architecture documentation in THROTTLE_SERVICE_HLD.md.