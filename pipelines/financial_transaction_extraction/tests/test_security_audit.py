"""Static security audits. They read the source (Java and Python), so they run on every `pytest` without a JVM,
and fail when a change reintroduces a class of bug. A failure is not always a bug: it means "review this",
and the whitelists below are where you record the decision."""
import re
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[3]
JAVA = REPO / "server" / "src" / "main" / "java" / "in" / "expensetrackerapp"
RESOURCES = REPO / "server" / "src" / "main" / "resources"
PIPELINE = Path(__file__).resolve().parents[1]

pytestmark = pytest.mark.skipif(not JAVA.exists(), reason="server/ sources not present next to pipelines/")


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"(?<!:)//[^\n]*", "", text)


def java_files(pattern="*.java"):
    return sorted(JAVA.rglob(pattern))


def strip_strings(text: str) -> str:
    return re.sub(r'"(\\.|[^"\\])*"', '""', text)


# ------------------------------------------------------------------ identity comes from the token, never the request
MAPPING = re.compile(r"@(?:Get|Post|Put|Patch|Delete)Mapping[^\n]*\n(?:\s*@[^\n]*\n)*\s*public\s+[^{]*?\(([^{]*?)\)\s*\{", re.S)


def controller_methods():
    for path in java_files("*Controller.java"):
        for match in MAPPING.finditer(strip_comments(path.read_text(encoding="utf-8"))):
            yield path, " ".join(match.group(1).split())


def test_there_are_controllers_to_audit():
    assert len(list(controller_methods())) >= 14


def test_every_endpoint_except_login_and_register_takes_the_authenticated_principal():
    for path, params in controller_methods():
        if path.name == "AuthController.java":
            continue
        assert "Authentication authentication" in params or "Principal principal" in params, f"{path.name}: endpoint ({params[:70]}...) has no authenticated principal"


def test_the_only_public_controller_is_the_auth_controller_and_it_exposes_only_register_and_login():
    source = strip_comments((JAVA / "auth" / "controller" / "AuthController.java").read_text())
    assert sorted(re.findall(r'@PostMapping\("(/[a-z]+)"\)', source)) == ["/login", "/register"]
    assert not re.search(r"@(Get|Put|Patch|Delete)Mapping", source)
    security = strip_comments((JAVA / "auth" / "security" / "SecurityConfig.java").read_text())
    assert re.findall(r'requestMatchers\(([^)]*)\)\.permitAll\(\)', security) == ['"/api/auth/**"', '"/actuator/health", "/actuator/health/**"']
    assert "anyRequest().authenticated()" in security


def split_params(params: str) -> list[str]:
    """Top-level comma split of a Java parameter list (commas inside (), <> and strings do not split)."""
    parts, depth, current, in_string = [], 0, "", False
    for ch in params:
        if ch == '"':
            in_string = not in_string
        if not in_string:
            if ch in "(<":
                depth += 1
            elif ch in ")>":
                depth -= 1
            elif ch == "," and depth == 0:
                parts.append(current.strip())
                current = ""
                continue
        current += ch
    if current.strip():
        parts.append(current.strip())
    return parts


def test_no_controller_parameter_can_carry_an_identity_from_the_client():
    checked = 0
    for path, params in controller_methods():
        for param in split_params(params):
            name = re.findall(r"(\w+)\s*$", param)[0]
            checked += 1
            injected = param in ("Authentication authentication", "Principal principal")      # filled in by Spring from the token
            assert injected or not re.search(r"(?i)user|owner|account|principal", name), \
                f"{path.name}: parameter '{param}' lets the client choose whose data is accessed"
    assert checked >= 25


def test_the_parameter_splitter_handles_real_signatures():
    assert split_params('Authentication authentication, @RequestParam(defaultValue = "0") int page, @RequestBody @Valid X body') == \
        ["Authentication authentication", '@RequestParam(defaultValue = "0") int page', "@RequestBody @Valid X body"]
    assert split_params("Map<String, String> headers, String userId") == ["Map<String, String> headers", "String userId"]


def test_request_bodies_cannot_carry_an_owner_or_hash_field():
    forbidden = re.compile(r"\b(userId|ownerId|sourceHash|gridFsFileId|passwordHash|role|roles)\b")
    for path in java_files("*Request.java"):
        source = strip_comments(path.read_text(encoding="utf-8"))
        assert not forbidden.search(source), f"{path.name} declares a field the client must never control"


# ------------------------------------------------------------------ unscoped data access is a reviewed, closed set
UNSCOPED_CALL = re.compile(r"\.(findById|deleteById|findAll|deleteAll|existsById|save|saveAll|insert|findOne)\(")
REVIEWED = {   # (file, call): why it is safe
    ("user/service/UserServiceImpl.java", "findById"): "id is the authenticated principal: a user reading themselves",
    ("auth/service/AuthServiceImpl.java", "save"): "registration creates the caller's own account",
    ("job/service/JobService.java", "insert"): "system creates the job for the uploading user (userId from the principal)",
    ("job/service/JobService.java", "findOne"): "worker-only transition; guarded by the lease owner; not exposed by any controller",
    ("document/service/DocumentService.java", "insert"): "creates the caller's own document",
    ("document/service/DocumentService.java", "deleteById"): "only after findByIdAndUserId succeeded, or to roll back the row just inserted",
    ("document/service/GridFsStorageService.java", "findOne"): "reached only through DocumentService after an owner check",
}


def test_every_unscoped_repository_call_has_been_reviewed():
    found = {}
    for path in java_files():
        rel = path.relative_to(JAVA).as_posix()
        for call in UNSCOPED_CALL.findall(strip_comments(path.read_text(encoding="utf-8"))):
            found.setdefault((rel, call), 0)
            found[(rel, call)] += 1
    unreviewed = set(found) - set(REVIEWED)
    assert not unreviewed, f"new unscoped data access, review it for cross-user exposure and add it to REVIEWED: {sorted(unreviewed)}"
    stale = set(REVIEWED) - set(found)
    assert not stale, f"REVIEWED lists a call that no longer exists: {sorted(stale)}"


def test_owner_scoped_lookups_are_the_only_way_services_load_a_single_document_or_transaction():
    for rel, finder in (("document/repository/DocumentRepository.java", "findByIdAndUserId"),
                        ("transaction/repository/TransactionRepository.java", "findByIdAndUserId"),
                        ("job/repository/JobRepository.java", "findByIdAndUserId")):
        assert finder in (JAVA / rel).read_text()
    for rel in ("document/service/DocumentService.java", "transaction/service/TransactionService.java"):
        assert ".findById(" not in (JAVA / rel).read_text()


# ------------------------------------------------------------------ logging
def log_calls(source: str):
    """Argument text of every log.<level>(...) call, found by matching parentheses (strings respected)."""
    for start in re.finditer(r"\blog\.(?:info|warn|error|debug|trace)\(", source):
        depth, i, in_string = 1, start.end(), False
        while i < len(source) and depth:
            ch = source[i]
            if in_string:
                if ch == "\\":
                    i += 1
                elif ch == '"':
                    in_string = False
            elif ch == '"':
                in_string = True
            elif ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            i += 1
        yield source[start.end():i - 1]


def test_logs_never_contain_credentials_tokens_or_unmasked_emails():
    for path in java_files():
        source = strip_comments(path.read_text(encoding="utf-8"))
        assert "System.out" not in source and "printStackTrace" not in source, path.name
        if path.name == "AuthServiceImpl.java":
            assert len(list(log_calls(source))) == 3                  # the audit really sees the calls it is meant to check
        for call in log_calls(source):
            code = strip_strings(call)
            for word in ("password", "Password", "passwordHash", "token", "Token", "secret", "Secret", "jwt", "authorization",
                         "Authorization", "getHeader", "getBody"):
                assert word not in code, f"{path.name}: log call mentions {word}: {call[:80]}"
            if re.search(r"(?i)email", code):
                assert "maskEmail(" in code, f"{path.name}: e-mail logged without masking: {call[:80]}"


# ------------------------------------------------------------------ configuration
def properties() -> dict:
    values = {}
    for line in (RESOURCES / "application.properties").read_text().splitlines():
        if line.strip() and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def test_no_secret_has_a_default_value():
    props = properties()
    assert props["application.jwt.secret"] == "${JWT_SECRET:}"                      # empty default: startup fails without it
    for key, value in props.items():
        if re.search(r"(?i)secret|password|token|api[-_.]?key", key):
            assert re.fullmatch(r"\$\{[A-Z_]+:?\}", value), f"{key} has a literal/default value"
    assert "@" not in props["spring.mongodb.uri"], "default Mongo URI must not embed credentials"


def test_error_responses_cannot_leak_internals_and_only_health_is_exposed():
    props = properties()
    assert props["server.error.include-stacktrace"] == "never" and props["server.error.include-message"] == "never"
    assert props["server.error.include-binding-errors"] == "never"
    assert props["management.endpoints.web.exposure.include"] == "health"
    assert props["management.endpoint.health.show-details"] == "never"


def test_cors_has_no_wildcards_and_no_credentials():
    cors = strip_comments((JAVA / "config" / "CorsConfig.java").read_text())
    assert "setAllowCredentials(false)" in cors
    assert 'setAllowedOrigins(List.of("*"))' not in cors and 'setAllowedHeaders(List.of("*"))' not in cors


def test_uploads_are_validated_by_signature_size_and_name_before_storage():
    service = (JAVA / "document" / "service" / "DocumentService.java").read_text()
    assert "FileSignatureDetector.detect" in service and "FilenameSanitizer.sanitize" in service
    assert service.index("detectType(file)") < service.index("storageService.store(")
    assert service.index("PayloadTooLargeException") < service.index("storageService.store(")
    controller = (JAVA / "document" / "controller" / "DocumentController.java").read_text()
    assert '"X-Content-Type-Options", "nosniff"' in controller and "ContentDisposition.attachment()" in controller
    assert "no-store" in controller


# ------------------------------------------------------------------ repository hygiene
SKIP_DIRS = {"node_modules", ".git", "__pycache__", ".pytest_cache", "target", "backups", ".idea", ".mvn"}
TEXT_SUFFIXES = {".java", ".py", ".properties", ".json", ".md", ".ts", ".tsx", ".js", ".yml", ".yaml", ".xml", ".txt",
                 ".ps1", ".sh", ".env", ".example", ".toml", ".cfg", ".ini", ".gitignore", ""}
SECRET_PATTERNS = [
    ("MongoDB URI with credentials", re.compile(r"mongodb(?:\+srv)?://[^\s\"'<>@/]+:[^\s\"'<>@/]+@")),
    ("AWS access key", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("Anthropic/OpenAI key", re.compile(r"sk-(?:ant-)?[A-Za-z0-9_-]{20,}")),
    ("private key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("GitHub token", re.compile(r"gh[pousr]_[A-Za-z0-9]{30,}")),
    ("Slack token", re.compile(r"xox[baprs]-[A-Za-z0-9-]{10,}")),
]
FAKE_MARKERS = ("adminpw", "worker-pw", "u:p@", "user:pass", "USER:PASS", "<password>", "<user>", "s3cret", "prod-host",
                "worker-user", "contract-user", "example", "localhost", "127.0.0.1", "admin:pw")


def repo_text_files():
    for path in REPO.rglob("*"):
        if path.is_file() and not (set(path.relative_to(REPO).parts) & SKIP_DIRS) and path.suffix in TEXT_SUFFIXES \
                and path.stat().st_size < 2_000_000:
            yield path


def test_no_real_looking_secret_is_committed_anywhere():
    findings = []
    for path in repo_text_files():
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for label, pattern in SECRET_PATTERNS:
            for match in pattern.finditer(text):
                if "{" in match.group(0) or any(marker in match.group(0) for marker in FAKE_MARKERS):
                    continue                                          # a format template or an obvious placeholder
                findings.append(f"{path.relative_to(REPO)}: {label}")
    assert findings == []


def test_no_environment_file_with_real_values_exists():
    real = [p.relative_to(REPO) for p in REPO.rglob(".env*")
            if p.is_file() and not (set(p.relative_to(REPO).parts) & SKIP_DIRS) and p.name != ".env.example"
            and not p.name.endswith(".example")]
    assert real == [], f"commit only .env.example files, found: {real}"


def test_gitignore_blocks_env_files_keys_and_personal_data():
    ignored = (REPO / ".gitignore").read_text() + (PIPELINE / ".gitignore").read_text()
    for entry in (".env", "backups/", "models/", "corrections*.csv", "!.env.example"):
        assert entry in ignored, f".gitignore lacks {entry}"


def test_example_env_files_contain_no_values_for_secrets():
    for example in (REPO / "server" / ".env.example", PIPELINE / ".env.example"):
        for line in example.read_text().splitlines():
            key, _, value = line.partition("=")
            if re.search(r"(?i)secret|password|key|token", key) and not line.startswith("#"):
                assert value.split("#")[0].strip() in ("", "false"), f"{example.name}: {key} has a value"
        assert "mongodb+srv://<" in example.read_text() or "MONGODB_URI" not in example.read_text()


# ------------------------------------------------------------------ Python worker
PY_SOURCES = [p for d in ("worker", "categorization", "migration") for p in (PIPELINE / d).rglob("*.py")]


def test_python_services_never_shell_out_or_evaluate_text():
    for path in PY_SOURCES:
        code = strip_comments(path.read_text()).replace("# ", "# ")
        for banned in ("subprocess", "os.system", "shell=True", "eval(", "exec(", "os.popen", "__import__("):
            assert banned not in code, f"{path.name}: {banned}"


def test_pickle_style_loading_is_confined_to_the_integrity_checked_model_loader():
    loaders = [p.relative_to(PIPELINE).as_posix() for p in PY_SOURCES if re.search(r"joblib\.load|pickle\.load", p.read_text())]
    assert loaders == ["categorization/model.py"]
    assert "ModelIntegrityError" in (PIPELINE / "categorization" / "model.py").read_text()


def test_the_worker_logs_through_the_structured_logger_only():
    for path in (PIPELINE / "worker").glob("*.py"):
        if path.name == "main.py":
            continue                                               # prints one configuration error before logging exists
        assert "print(" not in path.read_text(), f"{path.name} prints"


def test_temporary_files_are_created_private_and_removed():
    storage = (PIPELINE / "worker" / "gridfs_storage.py").read_text()
    assert "0o600" in storage and "O_EXCL" in storage
    worker = (PIPELINE / "worker" / "job_worker.py").read_text()
    assert "tempfile.TemporaryDirectory" in worker                  # created 0700, removed even on error
