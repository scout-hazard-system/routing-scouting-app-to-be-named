"""Multi-machine categorized blackboard shared memory for Scout Crew."""

from scout_crew.blackboard.client import BlackboardClient
from scout_crew.blackboard.store import CATEGORIES, ROLE_ACL

__all__ = ["BlackboardClient", "CATEGORIES", "ROLE_ACL"]
