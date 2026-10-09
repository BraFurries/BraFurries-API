"""Guard the public API production workflow's trust boundaries without extra dependencies."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
workflow = (ROOT / ".github/workflows/prod-deploy.yaml").read_text(encoding="utf-8")

assert "\non:\n" in workflow and "\njobs:\n" in workflow
triggers = workflow.split("\non:\n", 1)[1].split("\njobs:\n", 1)[0]
assert "workflow_dispatch:" in triggers
assert "push:\n    branches:\n      - main" in triggers  # push restricted to main
assert "pull_request:" not in triggers
assert "workflow_run:" not in triggers

assert "vars.BRF_API_AUTO_DEPLOY_ENABLED == 'true'" in workflow
assert "(github.event_name == 'push' && vars.BRF_API_AUTO_DEPLOY_ENABLED == 'true')" in workflow
assert "github.event_name == 'workflow_dispatch'" in workflow
assert "github.ref == 'refs/heads/main'" in workflow
assert "needs: deploy-secret-scan" in workflow
assert "needs: build-image" in workflow
assert "Refusing stale API deployment: main has advanced." in workflow

assert "group: api-production" in workflow
assert "labels: [self-hosted, BRFAPI]" in workflow
assert "name: Produção" in workflow
assert "PROD_ENV_FILE: ${{ secrets.PROD_ENV_FILE }}" in workflow

before_production, production = workflow.split("  deploy-production:\n", 1)
assert "runs-on: ubuntu-latest" in before_production
assert "actions/checkout@" not in production
assert "actions/setup-java@" not in production
assert "docker build" not in production
assert "docker compose" not in production
assert "mvnw" not in production
assert "pm2 " not in production.lower()
assert "run_migrations" not in workflow
assert "sudo -n /usr/local/sbin/deploy-brafurries-api-public" in production
assert "sudo -n /usr/local/sbin/deploy-brafurries-api deploy" not in production
assert "printf '%s\\n%s\\n%s' " in production  # gateway stdin envelope
assert "sudo -n /usr/local/sbin/update-brafurries-api-env stage" in production
assert "sudo -n /usr/local/sbin/update-brafurries-api-env discard" in production

for action, sha in re.findall(r"uses:\s+([^\s#]+)@([^\s#]+)", workflow):
    assert re.fullmatch("[a-f0-9]{40}", sha), f"unpinned action: {action}@{sha}"

print("Public API production deployment boundaries validated.")
