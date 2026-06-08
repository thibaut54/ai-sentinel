"""
Database adapter for fetching PII detection configuration from PostgreSQL.

This module provides a simple, reliable way to fetch the dynamic configuration
from the shared PostgreSQL database when requested via the gRPC fetch_config_from_db flag.
"""

import logging
import os
from typing import Optional

import psycopg2
from psycopg2.extras import RealDictCursor

logger = logging.getLogger(__name__)


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
            regex_enabled, default_threshold, llm_judge_enabled,
            prefilter_enabled.
            Returns None if fetch fails.

        Business Rule: Single-row configuration table with id=1.

        Note on ``llm_judge_enabled`` / ``prefilter_enabled``: both columns are
        fetched defensively via ``COALESCE`` so the adapter remains compatible
        with deployments that have not yet applied the migration adding these
        columns (see spec section 2.6).
        """
        connection = None
        cursor = None

        try:
            connection = self._get_connection()
            cursor = connection.cursor(cursor_factory=RealDictCursor)

            # Query the single-row configuration table.
            # ``openmed_enabled``, ``llm_judge_enabled`` and ``prefilter_enabled``
            # are read defensively: ``COALESCE`` / a UndefinedColumn fallback keep
            # existing rows readable even if the columns have not been added yet by
            # Hibernate DDL update on a freshly-pulled environment.
            query = """
                SELECT
                    gliner_enabled,
                    presidio_enabled,
                    regex_enabled,
                    COALESCE(openmed_enabled, FALSE) AS openmed_enabled,
                    COALESCE(gliner2_enabled, FALSE) AS gliner2_enabled,
                    default_threshold,
                    nb_of_label_by_pass,
                    llm_judge_enabled,
                    prefilter_enabled
                FROM pii_detection_config
                WHERE id = 1
            """

            try:
                cursor.execute(query)
                result = cursor.fetchone()
            except psycopg2.errors.UndefinedColumn:
                # Migration not yet applied for openmed_enabled, gliner2_enabled,
                # llm_judge_enabled or prefilter_enabled -- retry without the new
                # columns. They all default to FALSE downstream so the service
                # stays usable on a freshly-pulled environment before Hibernate
                # DDL update.
                logger.warning(
                    "openmed_enabled / gliner2_enabled / llm_judge_enabled / "
                    "prefilter_enabled column missing in pii_detection_config; "
                    "falling back on defaults (false). Apply migration to enable."
                )
                connection.rollback()
                cursor.execute(
                    """
                    SELECT
                        gliner_enabled,
                        presidio_enabled,
                        regex_enabled,
                        FALSE AS openmed_enabled,
                        FALSE AS gliner2_enabled,
                        default_threshold,
                        nb_of_label_by_pass
                    FROM pii_detection_config
                    WHERE id = 1
                    """
                )
                result = cursor.fetchone()

            if result is None:
                logger.warning(
                    "No configuration found in database (id=1). "
                    "Will use default configuration from TOML file."
                )
                return None

            config = dict(result)
            # Normalise the flags so downstream code can assume they are always
            # present (False when the migration is pending).
            config.setdefault("llm_judge_enabled", False)
            if config["llm_judge_enabled"] is None:
                config["llm_judge_enabled"] = False
            config.setdefault("prefilter_enabled", False)
            if config["prefilter_enabled"] is None:
                config["prefilter_enabled"] = False
            config.setdefault("gliner2_enabled", False)
            if config["gliner2_enabled"] is None:
                config["gliner2_enabled"] = False
            logger.info(
                "Successfully fetched config from database: "
                f"gliner={config['gliner_enabled']}, "
                f"presidio={config['presidio_enabled']}, "
                f"regex={config['regex_enabled']}, "
                f"openmed={config['openmed_enabled']}, "
                f"gliner2={config['gliner2_enabled']}, "
                f"threshold={config['default_threshold']}, "
                f"llm_judge={config['llm_judge_enabled']}, "
                f"prefilter={config['prefilter_enabled']}"
            )
            return config

        except psycopg2.OperationalError as e:
            logger.error(
                f"Database connection failed: {e}. "
                "Check DB_HOST, DB_PORT, DB_USER, DB_PASSWORD environment variables. "
                "Will use default configuration from TOML file."
            )
            return None

        except psycopg2.Error as e:
            logger.error(
                f"Database query failed: {e}. "
                "Will use default configuration from TOML file."
            )
            return None

        except Exception as e:
            logger.error(
                f"Unexpected error fetching config: {e}. "
                "Will use default configuration from TOML file."
            )
            return None

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

            # ``detector_description`` is read defensively: a freshly-pulled
            # environment where Hibernate DDL update has not yet added the
            # column must not crash. We COALESCE to NULL and, on UndefinedColumn,
            # retry without the column (description defaults to None downstream).
            results = self._execute_pii_type_query(cursor, connection, detector)

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
                    'detector_label': row['detector_label'],
                    # GLiNER2 inference description ({label: description}). None
                    # when the column is absent (pre-migration) or unset.
                    'detector_description': row.get('detector_description'),
                    # Per-type LLM-judge opt-out. Defaults to True (judge
                    # enabled) when the column is absent (pre-migration).
                    'llm_judge_enabled': (
                        True if row.get('llm_judge_enabled') is None
                        else bool(row['llm_judge_enabled'])
                    ),
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
            return None

        except psycopg2.Error as e:
            logger.error(
                f"Database query failed fetching PII type configs: {e}. "
                "Will use default TOML configuration."
            )
            return None

        except Exception as e:
            logger.error(
                f"Unexpected error fetching PII type configs: {e}. "
                "Will use default TOML configuration."
            )
            return None

        finally:
            if cursor:
                cursor.close()
            if connection:
                connection.close()

    def _execute_pii_type_query(self, cursor, connection, detector: Optional[str]):
        """Run the pii_type_config SELECT with defensive optional-column fallbacks.

        ``detector_description`` (used by GLiNER2 for {label: description}
        inference) and ``llm_judge_enabled`` (per-type judge opt-out) are
        fetched defensively: on a freshly-pulled environment where Hibernate
        DDL update has not yet created a column, ``psycopg2`` raises
        ``UndefinedColumn``. We then retry with fewer optional columns so the
        service stays usable (description defaults to ``None`` and the judge
        flag to ``True`` downstream).
        """
        base_columns = (
            "pii_type, detector, enabled, threshold, "
            "category, country_code, detector_label"
        )
        where_clause = "WHERE detector = %s\n" if detector else ""
        params = (detector,) if detector else None

        # Most-complete first; each fallback drops the newest optional column.
        optional_column_sets = (
            ", detector_description, llm_judge_enabled",
            ", detector_description",
            "",
        )
        for i, optional_columns in enumerate(optional_column_sets):
            query = (
                f"SELECT {base_columns}{optional_columns}\n"
                f"FROM pii_type_config\n{where_clause}ORDER BY category, pii_type"
            )
            try:
                cursor.execute(query, params)
                return cursor.fetchall()
            except psycopg2.errors.UndefinedColumn:
                if i == len(optional_column_sets) - 1:
                    raise
                self.logger.warning(
                    "Optional column(s) missing in pii_type_config "
                    "(tried '%s'); retrying with fewer columns. "
                    "Apply migrations to enable.",
                    optional_columns.strip(', ') or '<none>',
                )
                connection.rollback()
        return None  # pragma: no cover - unreachable


# Global singleton instance for reuse
_config_adapter = None


def get_database_config_adapter() -> DatabaseConfigAdapter:
    """Get or create the global DatabaseConfigAdapter instance."""
    global _config_adapter
    if _config_adapter is None:
        _config_adapter = DatabaseConfigAdapter()
    return _config_adapter
