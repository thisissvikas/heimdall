# Heimdall vs. AWS CloudWatch Synthetics

**Recommendation:** Build and deploy Heimdall in-house for the scale and internal workflows proposed in [plan.md](../plan.md). Its shared execution pools avoid CloudWatch Synthetics’ per-run service fee, while its integration interfaces let monitoring fit our identity, secrets, deployment, and observability systems. This is a design comparison; Heimdall’s capabilities and savings remain to be validated.

**Cost: a stronger economic model at scale.** Using AWS’s published US East example rate of **$0.0012 per canary run**, the proposal’s 10,000 monitors across three locations would cost:

| Frequency | Runs per 30-day month | AWS canary run fees alone |
|---|---:|---:|
| Every minute — proposed baseline | 1.296 billion | **$1,555,200/month** |
| Every five minutes | 259.2 million | **$311,040/month** |

Calculation: monitors × locations × runs/day × 30 × rate. Each complete workflow at each location is treated as one canary run, regardless of its HTTP step count. These illustrative list-price figures exclude free-tier allowances, negotiated discounts, and additional Lambda, logs, metrics, alarms, S3, and applicable networking charges; regional rates must be checked. [AWS pricing](https://aws.amazon.com/cloudwatch/pricing/).

Heimdall instead pays for provisioned compute, orchestration, storage, networking, and engineering/operations. Shared asynchronous HTTP pools and durable timers release execution capacity between polls. Existing infrastructure can be reused where capacity permits. There is no Synthetics fee per run, but Temporal Cloud remains usage-priced in the default proposal; self-hosted Temporal is an option. Approve rollout only after measuring fully loaded cost per 1,000 regional runs, including build and maintenance costs.

**Internal integrations: one reusable platform contract.**

| Area | CloudWatch Synthetics | Heimdall proposal and advantage |
|---|---|---|
| Identity and secrets | AWS IAM plus custom integration code where needed. | OIDC, scoped RBAC, OPA adapter, and Vault-compatible secrets integrate with internal access policies. |
| Delivery and incident workflows | AWS APIs and custom automation can connect external systems. | GitHub OIDC, Checks/JUnit, deployment gates, and authenticated webhooks provide shared contracts; internal release and incident tools can use the REST API/webhooks. |
| Private execution and telemetry | Supports private VPC endpoints; requires connectivity to CloudWatch and S3. | Outbound-connected private runners execute inside approved networks; OpenTelemetry/Prometheus export and Kafka result events enable internal dashboards and consumers. |

AWS can support internal integrations too; Heimdall’s advantage is owning and standardizing them across teams. [AWS canary architecture](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch_Synthetics_Canaries.html), [private networking](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch_Synthetics_Canaries_VPC.html).

**Workflow fit strengthens the case.** Heimdall explicitly supports recoverable workflows lasting minutes or hours. AWS limits an individual canary run to **14 minutes**, so longer workflows require additional orchestration. [AWS timeout reference](https://docs.aws.amazon.com/AmazonSynthetics/latest/APIReference/API_CanaryRunConfigInput.html).

CloudWatch offers faster adoption, managed execution, and browser monitoring today; Heimdall adds platform ownership and delivers browsers later. For the proposed API workload, high execution volume and reusable internal integrations favor Heimdall, subject to the plan’s capacity and cost acceptance gates.
