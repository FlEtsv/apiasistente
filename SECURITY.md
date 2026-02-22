# Security Policy

## Supported versions
Only the `main` branch is actively maintained.

## Reporting a vulnerability
Please do not open public GitHub issues for security vulnerabilities.

Report privately to:
- security@apiasistente.local

Include, when possible:
- Affected endpoint/module
- Reproduction steps
- Expected vs actual behavior
- Suggested mitigation (optional)

## Response process
1. Acknowledgement within 72 hours.
2. Triage and severity classification.
3. Fix and coordinated disclosure.

## Security baseline
- `/api/ext/**` requires API key (`X-API-KEY` or `Authorization: Bearer ...`).
- Web routes use session + CSRF protections.
- Secrets must come from environment variables, never hardcoded in source.
