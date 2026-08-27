from enum import StrEnum
from ipaddress import ip_address, ip_network
import re
from urllib.parse import urlparse


class AiDeploymentMode(StrEnum):
    LOCAL_ONLY = "LOCAL_ONLY"
    CLOUD_ALLOWED = "CLOUD_ALLOWED"


def local_only_policy_violations(allow_remote_inference: bool, allow_cloud_fallback: bool) -> list[str]:
    """Return inference policy flags that conflict with strict local-only mode."""
    violations = []
    if allow_remote_inference:
        violations.append("AI_ALLOW_REMOTE_INFERENCE")
    if allow_cloud_fallback:
        violations.append("AI_ALLOW_CLOUD_FALLBACK")
    return violations


_DOCKER_HOST_PATTERN = re.compile(r"^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")
_LOCAL_NETWORKS = tuple(
    ip_network(cidr)
    for cidr in (
        "127.0.0.0/8",
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "::1/128",
        "fc00::/7",
    )
)


def is_local_model_endpoint(endpoint: str) -> bool:
    """Return whether an HTTP endpoint is confined to a local/private network."""
    parsed = urlparse(endpoint)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
        return False

    hostname = parsed.hostname.rstrip(".").lower()
    if hostname in {"localhost", "host.docker.internal"}:
        return True

    try:
        address = ip_address(hostname)
    except ValueError:
        # Docker Compose service names are single-label DNS names. Dotted public
        # hostnames are deliberately rejected in strict local-only mode.
        return (
            "." not in hostname
            and not hostname.isdigit()
            and bool(_DOCKER_HOST_PATTERN.fullmatch(hostname))
        )

    return any(address in network for network in _LOCAL_NETWORKS)
