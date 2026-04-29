"""
Database adapter for fetching PII detection configuration from PostgreSQL.

This module provides a simple, reliable way to fetch the dynamic configuration
from the shared PostgreSQL database when requested via the gRPC fetch_config_from_db flag.
"""

import logging
import os
from pathlib import Path
from typing import Any, Optional

import psycopg2
from psycopg2.extras import RealDictCursor

try:
    import tomllib  # Python 3.11+
except ImportError:  # pragma: no cover
    import tomli as tomllib  # type: ignore

logger = logging.getLogger(__name__)


# Cache du TOML de fallback : chargement paresseux puis re-utilise.
# Cle = chemin absolu, valeur = dict parse.
_TEST_FALLBACK_CACHE: dict[str, dict[str, Any]] = {}


def _resolve_test_fallback_path() -> Optional[Path]:
    """Resolve the path to the test fallback TOML, if configured.

    Activated by env var ``PII_DETECTOR_TEST_FALLBACK_TOML`` which can be either:
      - an absolute path to a TOML file
      - a path relative to the pii-detector-service root

    Returns None when the env var is not set or the file is missing.
    """
    raw = os.getenv("PII_DETECTOR_TEST_FALLBACK_TOML")
    if not raw:
        return None
    candidate = Path(raw)
    if not candidate.is_absolute():
        # Resolve relative to pii-detector-service/ (5 levels up from this file)
        service_root = Path(__file__).resolve().parents[4]
        candidate = (service_root / candidate).resolve()
    if not candidate.is_file():
        logger.warning(
            "PII_DETECTOR_TEST_FALLBACK_TOML points to a non-existent file: %s",
            candidate,
        )
        return None
    return candidate


def _load_test_fallback() -> Optional[dict]:
    """Load and cache the test fallback TOML if configured."""
    path = _resolve_test_fallback_path()
    if path is None:
        return None
    key = str(path)
    if key in _TEST_FALLBACK_CACHE:
        return _TEST_FALLBACK_CACHE[key]
    try:
        with path.open("rb") as fh:
            data = tomllib.load(fh)
    except Exception as exc:  # noqa: BLE001
        logger.warning("Could not parse test fallback TOML %s: %s", path, exc)
        return None
    _TEST_FALLBACK_CACHE[key] = data
    logger.info(
        "Loaded test fallback PII config from %s (%d pii_types entries)",
        path,
        len(data.get("pii_types", [])),
    )
    return data


def _fallback_detection_config() -> Optional[dict]:
    """Build a fetch_config()-shaped dict from the test fallback TOML."""
    data = _load_test_fallback()
    if data is None:
        return None
    cfg = data.get("detection_config")
    if not cfg:
        return None
    # Mirror RealDictRow shape returned by the live SQL query.
    return {
        "gliner_enabled": bool(cfg.get("gliner_enabled", True)),
        "presidio_enabled": bool(cfg.get("presidio_enabled", True)),
        "regex_enabled": bool(cfg.get("regex_enabled", False)),
        "default_threshold": float(cfg.get("default_threshold", 0.5)),
        "nb_of_label_by_pass": int(cfg.get("nb_of_label_by_pass", 35)),
        "llm_validation_enabled": bool(cfg.get("llm_validation_enabled", False)),
    }


def _fallback_pii_type_configs(detector: Optional[str]) -> Optional[dict]:
    """Build a fetch_pii_type_configs()-shaped dict from the test fallback TOML.

    Same shape as the live method (primary key = pii_type, optional composite
    key ``DETECTOR:PII_TYPE`` when no detector filter was passed).
    """
    data = _load_test_fallback()
    if data is None:
        return None
    rows = data.get("pii_types", [])
    if not rows:
        return None
    configs: dict[str, dict[str, Any]] = {}
    for row in rows:
        if detector and row.get("detector") != detector:
            continue
        pii_type = row.get("pii_type")
        if not pii_type:
            continue
        entry = {
            "enabled": bool(row.get("enabled", False)),
            "threshold": float(row.get("threshold", 0.5)),
            "detector": row.get("detector"),
            "category": row.get("category"),
            "country_code": row.get("country_code"),
            "detector_label": row.get("detector_label"),
        }
        configs[pii_type] = entry
        if not detector:
            configs[f"{row.get('detector')}:{pii_type}"] = entry
    if not configs:
        return None
    return configs


class DatabaseConfigAdapter:
    """Adapter for fetching PII detection configuration from PostgreSQL."""

    def __init__(self):
        """Initialize database connection parameters from environment variables."""
        self.host = os.getenv("DB_HOST", "postgres")
        self.port = int(os.getenv("DB_PORT", "5432"))
        self.database = os.getenv("DB_NAME", "ai-sentinel")
        self.user = os.getenv("DB_USER")
        self.password = os.getenv("DB_PASSWORD")
        if not self.user or not self.password:
           logger.warning(
               "DB_USER or DB_PASSWORD not set. Database connections will fail."
           )

    def _get_connection(self):
        """Create and return a database connection."""
        return psycopg2.connect(
            host=self.host,
            port=self.port,
            database=self.database,
            user=self.user,
            password=self.password,
            connect_timeout=5,
        )

    def fetch_config(self) -> Optional[dict]:
        """
        Fetch PII detection configuration from database.

        Returns:
            Dictionary with config keys: gliner_enabled, presidio_enabled,
            regex_enabled, default_threshold. Returns None if fetch fails.

        Business Rule: Single-row configuration table with id=1
        """
        connection = None
        cursor = None

        try:
            connection = self._get_connection()
            cursor = connection.cursor(cursor_factory=RealDictCursor)

            # Query the single-row configuration table
            query = """
                SELECT
                    gliner_enabled,
                    presidio_enabled,
                    regex_enabled,
                    default_threshold,
                    nb_of_label_by_pass,
                    llm_validation_enabled
                FROM pii_detection_config
                WHERE id = 1
            """

            cursor.execute(query)
            result = cursor.fetchone()

            if result is None:
                logger.warning(
                    "No configuration found in database (id=1). "
                    "Will use default configuration from TOML file."
                )
                return None

            config = dict(result)
            logger.info(
                "Successfully fetched config from database: "
                f"gliner={config['gliner_enabled']}, "
                f"presidio={config['presidio_enabled']}, "
                f"regex={config['regex_enabled']}, "
                f"threshold={config['default_threshold']}, "
                f"llm_validation={config.get('llm_validation_enabled', False)}"
            )
            return config

        except psycopg2.OperationalError as e:
            logger.error(
                f"Database connection failed: {e}. "
                "Check DB_HOST, DB_PORT, DB_USER, DB_PASSWORD environment variables. "
                "Will use default configuration from TOML file."
            )
            return _fallback_detection_config()

        except psycopg2.Error as e:
            logger.error(
                f"Database query failed: {e}. "
                "Will use default configuration from TOML file."
            )
            return _fallback_detection_config()

        except Exception as e:
            logger.error(
                f"Unexpected error fetching config: {e}. "
                "Will use default configuration from TOML file."
            )
            return _fallback_detection_config()

        finally:
            if cursor:
                cursor.close()
            if connection:
                connection.close()

    def fetch_pii_type_configs(self, detector: str = None) -> Optional[dict]:
        """
        Fetch PII type-specific configurations from database.

        Args:
            detector: Optional detector filter ('GLINER', 'PRESIDIO', 'REGEX').
                     If None, fetches all PII type configs.

        Returns:
            Dictionary mapping PII type to config dict with keys:
            - enabled (bool): Whether this PII type is enabled
            - threshold (float): Detection threshold for this type (0.0-1.0)
            - detector (str): Detector name (GLINER, PRESIDIO, REGEX)
            - display_name (str): Human-readable name
            - category (str): PII category
            
            Example: {
                'EMAIL': {'enabled': True, 'threshold': 0.5, 'detector': 'GLINER', ...},
                'CREDIT_CARD': {'enabled': False, 'threshold': 0.7, 'detector': 'PRESIDIO', ...}
            }
            
            Returns None if fetch fails.

        Business Rule: PII type configs control which types are detected and
        their confidence thresholds on a per-type basis.
        """
        self.logger = logging.getLogger(f"{__name__}.{self.__class__.__name__}")

        connection = None
        cursor = None

        try:
            connection = self._get_connection()
            cursor = connection.cursor(cursor_factory=RealDictCursor)

            # Build query with optional detector filter
            if detector:
                self.logger.info(f"Detector filter: {detector}")
                query = """
                    SELECT 
                        pii_type,
                        detector,
                        enabled,
                        threshold,
                        category,
                        country_code,
                        detector_label
                    FROM pii_type_config
                    WHERE detector = %s
                    ORDER BY category, pii_type
                """
                cursor.execute(query, (detector,))
            else:
                self.logger.info("No Detector")
                query = """
                    SELECT 
                        pii_type,
                        detector,
                        enabled,
                        threshold,
                        category,
                        country_code,
                        detector_label
                    FROM pii_type_config
                    ORDER BY category, pii_type
                """
                cursor.execute(query)

            results = cursor.fetchall()

            if not results:
                logger.warning(
                    f"No PII type configurations found in database "
                    f"(detector={detector or 'ALL'}). "
                    "Will use default TOML configuration."
                )
                return None

            # Build dictionary keyed by PII type
            configs = {}
            for row in results:
                pii_type = row['pii_type']
                entry = {
                    'enabled': row['enabled'],
                    'threshold': float(row['threshold']),
                    'detector': row['detector'],
                    'category': row['category'],
                    'country_code': row['country_code'],
                    'detector_label': row['detector_label']
                }
                # Primary key (may overwrite for duplicates across detectors)
                configs[pii_type] = entry
                # Composite key for precise per-detector lookup (always unique)
                if not detector:
                    configs[f"{row['detector']}:{pii_type}"] = entry

            logger.info(
                f"Successfully fetched {len(configs)} PII type configs from database "
                f"(detector={detector or 'ALL'})"
            )
            
            # Log sample of configs for debugging
            sample_types = list(configs.keys())[:3]
            for pii_type in sample_types:
                cfg = configs[pii_type]
                logger.debug(
                    f"  {pii_type}: enabled={cfg['enabled']}, "
                    f"threshold={cfg['threshold']}, detector={cfg['detector']}"
                )
            
            return configs

        except psycopg2.OperationalError as e:
            logger.error(
                f"Database connection failed fetching PII type configs: {e}. "
                "Check DB_HOST, DB_PORT, DB_USER, DB_PASSWORD environment variables. "
                "Will use default TOML configuration."
            )
            return _fallback_pii_type_configs(detector)

        except psycopg2.Error as e:
            logger.error(
                f"Database query failed fetching PII type configs: {e}. "
                "Will use default TOML configuration."
            )
            return _fallback_pii_type_configs(detector)

        except Exception as e:
            logger.error(
                f"Unexpected error fetching PII type configs: {e}. "
                "Will use default TOML configuration."
            )
            return _fallback_pii_type_configs(detector)

        finally:
            if cursor:
                cursor.close()
            if connection:
                connection.close()


# Global singleton instance for reuse
_config_adapter = None


def get_database_config_adapter() -> DatabaseConfigAdapter:
    """Get or create the global DatabaseConfigAdapter instance."""
    global _config_adapter
    if _config_adapter is None:
        _config_adapter = DatabaseConfigAdapter()
    return _config_adapter
