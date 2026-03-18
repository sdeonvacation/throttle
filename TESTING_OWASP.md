# Testing OWASP Dependency Check Locally

To test OWASP Dependency Check with your NVD API key locally:

```bash
# Export your API key
export NVD_API_KEY="your-api-key-here"

# Run with the API key
mvn clean verify -Ddependency-check.skip=false -Dnvd.api.key=$NVD_API_KEY
```

This should now work and complete in 2-3 minutes instead of hours/failing.

## How it works:

1. **GitHub Actions**: The workflow at `.github/workflows/security.yml` reads the secret `NVD_API_KEY` and passes it as `-Dnvd.api.key=$NVD_API_KEY`
2. **Maven**: The pom.xml reads this via `${nvd.api.key}` property
3. **Local**: You export the env var and pass it the same way

## Verify the secret is set correctly:

1. Go to GitHub repo → Settings → Secrets and variables → Actions
2. You should see `NVD_API_KEY` listed under "Repository secrets"
3. The value should be your actual API key from NVD (starts with something like `a1b2c3d4-...`)

## Common issues:

- **"API key not provided"**: The `-Dnvd.api.key` parameter wasn't passed correctly
- **"403/404 error"**: The API key is invalid or expired
- **Takes forever**: No API key is being used, falling back to slow unauthenticated access
