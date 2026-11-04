/** Presentation only: keep identifiers, credentials, numeric facts and source records intact. */
const displayFields = new Set([
  'displayName', 'hospitalName', 'name', 'title', 'metricName', 'indicatorName',
  'standard', 'appliesTo', 'sourceLocator', 'evaluationMethod', 'evidenceType', 'assignee',
  'deptName', 'roleName', 'detail', 'note', 'message', 'description', 'statement',
  'action', 'actionText', 'rootCause', 'reviewNote', 'returnReason', 'owner',
  'assigneePost', 'submitter', 'reviewer', 'requirement', 'requirementText',
  'mainDiagnosis', 'numeratorText', 'denominatorText', 'includeText', 'excludeText',
]);

export function displayText(value: string): string {
  return value
    .replace(/\s*[（(](?:演示|合成数据|合成|虚构)[）)]/g, '')
    .replace(/(?:本地)?演示环境\s*[/·]\s*合成数据/g, '')
    .replace(/(?:演示|合成)(?:数据)?[：:]\s*/g, '')
    .replace(/演示|合成/g, '')
    .trim();
}

export function present<T>(value: T): T {
  if (Array.isArray(value)) return value.map(item => present(item)) as T;
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [
      key, typeof item === 'string' && displayFields.has(key) ? displayText(item)
        : key === 'warnings' && Array.isArray(item) ? item.map(entry => typeof entry === 'string' ? displayText(entry) : present(entry))
        : present(item),
    ])) as T;
  }
  return value;
}
