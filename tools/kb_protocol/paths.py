"""Repository locations used by the tools."""
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent.parent
REPO_ROOT = TOOLS_DIR.parent
PROTOCOL_DIR = REPO_ROOT / "protocol"
DOCS_DIR = REPO_ROOT / "docs"
LOCAL_DIR = REPO_ROOT / "local"
REPORTS_DIR = LOCAL_DIR / "reports"
