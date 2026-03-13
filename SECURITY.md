# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |

## Reporting a Vulnerability

The Throttle team takes security vulnerabilities seriously. We appreciate your efforts to responsibly disclose your findings.

### How to Report

**Please do NOT report security vulnerabilities through public GitHub issues.**

Instead, please report security vulnerabilities by:
1. Opening a private security advisory on GitHub
2. Or emailing the maintainers (see contact information below)

### What to Include

Please include the following information in your report:
- Type of vulnerability
- Full paths of source file(s) related to the vulnerability
- Location of the affected source code (tag/branch/commit or direct URL)
- Step-by-step instructions to reproduce the issue
- Proof-of-concept or exploit code (if possible)
- Impact of the issue, including how an attacker might exploit it

### What to Expect

- **Acknowledgment**: We will acknowledge receipt of your vulnerability report within 48 hours.
- **Assessment**: We will assess the vulnerability and determine its impact within 7 days.
- **Fix Timeline**: We will work to fix confirmed vulnerabilities as quickly as possible:
  - **Critical**: Within 7 days
  - **High**: Within 14 days
  - **Medium/Low**: Within 30 days
- **Disclosure**: We will notify you when the vulnerability is fixed and coordinate public disclosure.

## Security Best Practices

When using Throttle:

1. **Resource Thresholds**: Set appropriate CPU and memory thresholds for your environment to prevent system overload.

2. **Task Implementation**: Ensure your task implementations:
   - Don't expose sensitive data in task IDs or metrics
   - Handle exceptions properly in `processChunk()`
   - Don't store sensitive information in task state

3. **Queue Capacity**: Configure appropriate queue capacity and overflow policies to prevent denial-of-service via task submission flooding.

4. **Monitoring**: Use the built-in metrics to detect anomalous behavior:
   - Unusually high task failure rates
   - Excessive pause/resume cycles
   - Queue saturation

5. **Thread Pools**: Provide appropriately sized thread pools to prevent resource exhaustion.

6. **Custom Monitors**: If implementing custom resource monitors, ensure they:
   - Don't leak sensitive information
   - Handle exceptions gracefully
   - Don't block indefinitely

## Known Security Considerations

### Resource Exhaustion
The executor is designed to prevent resource exhaustion, but:
- Malicious tasks can still consume resources during chunk execution
- Extremely small chunk sizes can cause overhead
- Very large queues can consume significant memory

**Mitigation**: Configure appropriate thresholds, queue sizes, and task termination policies.

### Information Disclosure
Task metrics and IDs may contain sensitive information if your task implementation includes it.

**Mitigation**: Don't include sensitive data in task IDs or observable fields.

## Security Updates

Security updates will be released as patch versions (e.g., 1.0.1) and announced via:
- GitHub Security Advisories
- Release notes in CHANGELOG.md
- GitHub Releases page

## Contact

For security-related questions or concerns, please open a GitHub issue (for non-sensitive questions) or contact the maintainers directly (for sensitive matters).

## Acknowledgments

We appreciate the security research community and will acknowledge reporters who responsibly disclose vulnerabilities (with their permission).
