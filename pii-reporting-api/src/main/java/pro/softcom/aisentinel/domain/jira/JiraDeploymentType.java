package pro.softcom.aisentinel.domain.jira;

/**
 * Type of Jira deployment.
 * Determines which REST API version and adapter to use.
 */
public enum JiraDeploymentType {

    CLOUD,
    DATA_CENTER
}
