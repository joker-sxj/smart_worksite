<script lang="ts">
import type { QaEvidenceStatus, QaMessage } from '../../api/types';

export const EVIDENCE_RECOVERY_PROMPT = '请在下方问题框补充地区、时间、对象或指定标准名称后重新发送。';
export const evidenceStatusMeta: Record<Exclude<QaEvidenceStatus, 'SUFFICIENT'>, { label: string; meaning: string; tagType: 'warning' | 'danger' | 'info' }> = {
  PARTIAL: { label: '证据部分充分', meaning: '部分内容已有证据支持，但仍有信息未覆盖。', tagType: 'warning' },
  INSUFFICIENT: { label: '证据不足', meaning: '现有资料不足以形成可靠回答。', tagType: 'warning' },
  CONFLICT: { label: '证据存在冲突', meaning: '不同来源的信息互相冲突，暂不宜直接下结论。', tagType: 'danger' },
  VALIDITY_UNKNOWN: { label: '资料有效性未知', meaning: '资料的适用地区或有效时间尚未确认。', tagType: 'warning' },
  RETRIEVAL_DEGRADED: { label: '检索能力已降级', meaning: '部分检索能力不可用，当前结果可能不完整。', tagType: 'warning' },
  TIMEOUT: { label: '检索超时', meaning: '检索未在时限内完成，当前结果可能不完整。', tagType: 'info' }
};

export function qaEvidenceRecovery(message: QaMessage & Record<string, unknown>) {
  const messageStatus = String(message.status || '').toUpperCase();
  const role = String(message.role || 'assistant').toLowerCase();
  if (role !== 'assistant' || message.pending || messageStatus === 'FAILED') return null;
  const evidenceStatus = String(message.retrievalDiagnostics?.evidenceStatus || message.evidenceStatus || '').toUpperCase();
  return evidenceStatusMeta[evidenceStatus as keyof typeof evidenceStatusMeta] || null;
}

export function qaValidityCaution(message: QaMessage & Record<string, unknown>) {
  const messageStatus = String(message.status || '').toUpperCase();
  const role = String(message.role || 'assistant').toLowerCase();
  const validityStatus = String(message.retrievalDiagnostics?.validityStatus || '').toUpperCase();
  if (role !== 'assistant' || message.pending || messageStatus === 'FAILED' || validityStatus !== 'UNKNOWN') return null;
  return '资料有效性未知：请结合地区、时间、对象或指定标准名称谨慎使用。';
}

export function appendQaSubmission<T, U, V>(existing: T[], userMessage: U, pendingMessage: V): Array<T | U | V> {
  return [...existing, userMessage, pendingMessage];
}

export function qaSuggestedFollowUps(message: QaMessage & Record<string, unknown>) {
  const messageStatus = String(message.status || '').toUpperCase();
  const suggestionStatus = String(message.suggestionStatus || '').toUpperCase();
  const role = String(message.role || 'assistant').toLowerCase();
  if (role !== 'assistant' || message.pending || messageStatus !== 'SUCCESS' || suggestionStatus !== 'SUCCESS') return [];
  if (!Array.isArray(message.suggestedFollowUpQuestions)) return [];
  return message.suggestedFollowUpQuestions
    .filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
    .map((item) => item.trim())
    .slice(0, 3);
}

export interface SuggestedFollowUpSubmissionState {
  pending: boolean;
  submittedKeys: Set<string>;
}

export async function runSuggestedFollowUpSubmission(
  state: SuggestedFollowUpSubmissionState,
  key: string,
  submit: () => Promise<void>
) {
  if (state.pending || state.submittedKeys.has(key)) return false;
  state.pending = true;
  try {
    await submit();
    state.submittedKeys.add(key);
    return true;
  } finally {
    state.pending = false;
  }
}
</script>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import EmptyState from '../../components/common/EmptyState.vue';
import { fetchDataSources } from '../../api/datasource';
import { fetchKnowledgeBases } from '../../api/knowledge';
import { archiveQaSession, createQaSession, fetchQaMessageDetail, fetchQaMessageReferences, fetchQaMessages, fetchQaSessionDetail, fetchQaSessions, regenerateMessage, sendQuestion, submitFeedback, updateQaSession } from '../../api/qa';
import { useProjectStore } from '../../stores/project';
import { useUserStore } from '../../stores/user';
import type { DataSourceItem, ID, KnowledgeBase, QaMessageSendRequest, QaSession } from '../../api/types';
import { hasSuspiciousText } from '../../utils/textQuality';
import { renderQaMarkdown } from '../../utils/qaMarkdown';
import { hasActiveQaGeneration, normalizeQaMessages, qaMessageText, restoreSubmittedSuggestionKeys } from './qaMessagePolling';

type QaMessageExtra = QaMessage & Record<string, unknown>;

const projectStore = useProjectStore();
const userStore = useUserStore();
const sessionLoading = ref(false);
const messageLoading = ref(false);
const sending = ref(false);
const sessionError = ref('');
const messageError = ref('');
const question = ref('');
const sessions = ref<QaSession[]>([]);
const activeSessionId = ref<ID>('');
const messages = ref<QaMessageExtra[]>([]);
const renameDialogVisible = ref(false);
const detailDrawerVisible = ref(false);
const detailLoading = ref(false);
const selectedMessage = ref<QaMessageExtra | null>(null);
const selectedReferences = ref<unknown[]>([]);
const renameTitle = ref('');
const renaming = ref(false);
const regeneratingId = ref<ID>('');
const feedbackMap = ref<Record<string, boolean>>({});
const routeMode = ref<'AUTO' | 'MODEL' | 'KNOWLEDGE' | 'DATABASE' | 'MIXED'>('AUTO');
const knowledgeBases = ref<KnowledgeBase[]>([]);
const dataSources = ref<DataSourceItem[]>([]);
const selectedKnowledgeBaseIds = ref<ID[]>([]);
const knowledgeScope = ref<'PROJECT' | 'POLICY' | 'ALL'>('PROJECT');
const selectedDataSourceId = ref<ID | ''>('');
const resourceLoading = ref(false);
const resourceError = ref('');
const activeSend = ref<{ token: string; userMessageId: ID; pendingMessageId: ID; content: string } | null>(null);
const suggestionSubmission = ref<SuggestedFollowUpSubmissionState>({ pending: false, submittedKeys: new Set<string>() });
let messagePollTimer: ReturnType<typeof setTimeout> | null = null;
const MESSAGE_POLL_INTERVAL_MS = 2000;

const activeSession = computed(() => sessions.value.find((item) => String(item.sessionId) === String(activeSessionId.value)) || null);
const enabledKnowledgeBases = computed(() => knowledgeBases.value.filter((item) => ['ENABLED', 'ACTIVE'].includes(String(item.status).toUpperCase())));
const scopedKnowledgeBases = computed(() => enabledKnowledgeBases.value.filter((item) => {
  const type = String(item.knowledgeBaseType || 'PROJECT').toUpperCase();
  return knowledgeScope.value === 'ALL' || type === knowledgeScope.value;
}));

function selectScopeKnowledgeBases() {
  selectedKnowledgeBaseIds.value = scopedKnowledgeBases.value.map((item) => item.knowledgeBaseId);
}

watch(knowledgeScope, selectScopeKnowledgeBases);
const enabledDataSources = computed(() => dataSources.value.filter((item) => ['ENABLED', 'ACTIVE'].includes(String(item.status).toUpperCase())));
const canManageQa = computed(() => userStore.hasPermission('qa:manage'));
const qaManageTip = '当前账号没有问答操作权限';

function t(text: string) { return text; }
function localId() { return Number(`${Date.now()}${Math.floor(Math.random() * 1000).toString().padStart(3, '0')}`); }
function messageRole(msg: QaMessageExtra) {
  return String(msg.role || 'assistant').toLowerCase();
}

function messageText(msg: QaMessageExtra) {
  return qaMessageText(msg);
}

function messageEvidenceMeta(msg: QaMessageExtra) {
  return qaEvidenceRecovery(msg);
}

function messageValidityCaution(msg: QaMessageExtra) {
  return qaValidityCaution(msg);
}

function messageSuggestions(msg: QaMessageExtra) {
  return qaSuggestedFollowUps(msg);
}

function suggestionKey(msg: QaMessageExtra, index: number) {
  return `${msg.messageId}:${index}`;
}

function stopMessagePolling() {
  if (messagePollTimer) clearTimeout(messagePollTimer);
  messagePollTimer = null;
}

function scheduleMessagePolling(sessionId: ID) {
  stopMessagePolling();
  if (!hasActiveQaGeneration(messages.value)) return;
  messagePollTimer = setTimeout(async () => {
    if (String(activeSessionId.value) !== String(sessionId)) return;
    try {
      messages.value = normalizeQaMessages(await fetchQaMessages(sessionId) as QaMessageExtra[]);
      messageError.value = '';
    } catch (err) {
      messageError.value = err instanceof Error ? err.message : t('回答状态刷新失败，请稍后重试。');
    } finally {
      if (String(activeSessionId.value) === String(sessionId)) scheduleMessagePolling(sessionId);
    }
  }, MESSAGE_POLL_INTERVAL_MS);
}

function renderMessageHtml(msg: QaMessageExtra) {
  return renderQaMarkdown(messageText(msg));
}

function createLocalUserMessage(sessionId: ID, projectId: ID, content: string): QaMessageExtra {
  const now = new Date().toISOString();
  const id = localId();
  return { messageId: id, sessionId, projectId, role: 'user', content, question: content, status: 'SUCCESS', createdAt: now, updatedAt: now };
}

function createErrorAssistantMessage(sessionId: ID, projectId: ID, content: string): QaMessageExtra {
  const now = new Date().toISOString();
  const id = localId();
  return { messageId: id, sessionId, projectId, role: 'assistant', content, answer: content, status: 'FAILED', createdAt: now, updatedAt: now, references: [] };
}

function createPendingAssistantMessage(sessionId: ID, projectId: ID): QaMessageExtra {
  const now = new Date().toISOString();
  const id = `pending-${localId()}`;
  return { messageId: id, sessionId, projectId, role: 'assistant', content: '正在生成回答，请稍候...', answer: '正在生成回答，请稍候...', status: 'RUNNING', createdAt: now, updatedAt: now, references: [], pending: true };
}

async function loadResources(projectId: ID) {
  resourceLoading.value = true;
  resourceError.value = '';
  try {
    const [bases, sources] = await Promise.all([
      fetchKnowledgeBases(projectId),
      fetchDataSources({ projectId, pageNo: 1, pageSize: 100 })
    ]);
    knowledgeBases.value = bases;
    dataSources.value = sources.records;
    selectScopeKnowledgeBases();
    if (selectedDataSourceId.value && !enabledDataSources.value.some((item) => String(item.dataSourceId) === String(selectedDataSourceId.value))) {
      selectedDataSourceId.value = '';
    }
  } catch (err) {
    knowledgeBases.value = [];
    dataSources.value = [];
    selectedKnowledgeBaseIds.value = [];
    selectedDataSourceId.value = '';
    resourceError.value = err instanceof Error ? err.message : t('问答资源加载失败，请检查知识库和数据源接口。');
  } finally {
    resourceLoading.value = false;
  }
}

async function loadSessions(selectId?: ID) {
  sessionLoading.value = true;
  sessionError.value = '';
  try {
    if (!projectStore.currentProject) await projectStore.fetchProjects();
    const projectId = projectStore.currentProject?.projectId;
    if (!projectId) {
      sessions.value = [];
      activeSessionId.value = '';
      messages.value = [];
      return;
    }
    await loadResources(projectId);
    sessions.value = await fetchQaSessions(projectId);
    const next = selectId || activeSessionId.value || sessions.value[0]?.sessionId || '';
    activeSessionId.value = next;
    if (next) await switchSession(next);
  } catch (err) {
    sessionError.value = err instanceof Error ? err.message : t('问答会话加载失败，请检查后端问答接口。');
    sessions.value = [];
  } finally {
    sessionLoading.value = false;
  }
}

async function switchSession(sessionId: ID) {
  stopMessagePolling();
  activeSessionId.value = sessionId;
  messageLoading.value = true;
  messageError.value = '';
  messages.value = [];
  try {
    await fetchQaSessionDetail(sessionId);
    messages.value = normalizeQaMessages(await fetchQaMessages(sessionId) as QaMessageExtra[]);
    scheduleMessagePolling(sessionId);
  } catch (err) {
    messageError.value = err instanceof Error ? err.message : t('会话消息加载失败，请检查后端问答接口。');
  } finally {
    messageLoading.value = false;
  }
}

function openRenameSession(item = activeSession.value) {
  if (!item) return ElMessage.warning('请先选择会话');
  renameTitle.value = item.title;
  renameDialogVisible.value = true;
}

async function submitRenameSession() {
  if (!canManageQa.value) return ElMessage.warning(qaManageTip);
  if (!activeSessionId.value) return ElMessage.warning('请先选择会话');
  if (!renameTitle.value.trim()) return ElMessage.warning('请输入会话标题');
  renaming.value = true;
  try {
    const updated = await updateQaSession(activeSessionId.value, { title: renameTitle.value.trim() });
    const target = sessions.value.find((item) => String(item.sessionId) === String(updated.sessionId));
    if (target) Object.assign(target, updated);
    renameDialogVisible.value = false;
    ElMessage.success('会话标题已更新');
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '会话更新失败');
  } finally {
    renaming.value = false;
  }
}

async function archiveSession(item = activeSession.value) {
  if (!canManageQa.value) return ElMessage.warning(qaManageTip);
  if (!item) return ElMessage.warning('请先选择会话');
  try {
    await ElMessageBox.confirm(`确认归档会话“${item.title}”？`, '归档会话', { type: 'warning' });
    await archiveQaSession(item.sessionId);
    ElMessage.success('会话已归档');
    await loadSessions();
  } catch (err) {
    if (err === 'cancel' || err === 'close') return;
    ElMessage.error(err instanceof Error ? err.message : '会话归档失败');
  }
}

async function regenerateAnswer(msg: QaMessageExtra) {
  if (!canManageQa.value) return ElMessage.warning(qaManageTip);
  if (!activeSessionId.value) return ElMessage.warning('请先选择会话');
  regeneratingId.value = msg.messageId;
  try {
    const answer = await regenerateMessage(activeSessionId.value, msg.messageId) as QaMessageExtra;
    messages.value.push({ ...answer, role: 'assistant', content: answer.answer || answer.content || '' });
    ElMessage.success('答案已重新生成');
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '重新生成失败');
  } finally {
    regeneratingId.value = '';
  }
}

async function openMessageDetail(msg: QaMessageExtra) {
  detailDrawerVisible.value = true;
  detailLoading.value = true;
  selectedMessage.value = null;
  selectedReferences.value = [];
  try {
    const [detail, refs] = await Promise.all([fetchQaMessageDetail(msg.messageId), fetchQaMessageReferences(msg.messageId)]);
    selectedMessage.value = detail as QaMessageExtra;
    selectedReferences.value = refs;
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '消息详情加载失败');
  } finally {
    detailLoading.value = false;
  }
}

async function newSession() {
  if (!canManageQa.value) return ElMessage.warning(qaManageTip);
  const projectId = projectStore.currentProject?.projectId;
  if (!projectId) return ElMessage.warning(t('请先选择项目'));
  sessionLoading.value = true;
  sessionError.value = '';
  try {
    const created = await createQaSession({ projectId, title: `${t('新建会话')} ${sessions.value.length + 1}` });
    sessions.value.unshift(created);
    activeSessionId.value = created.sessionId;
    messages.value = [];
  } catch (err) {
    sessionError.value = err instanceof Error ? err.message : t('问答会话加载失败，请检查后端问答接口。');
  } finally {
    sessionLoading.value = false;
  }
}

function buildQuestionPayload(content: string, extra: Partial<QaMessageSendRequest> = {}): QaMessageSendRequest {
  return {
    question: content,
    routeMode: routeMode.value,
    knowledgeBaseIds: ['AUTO', 'KNOWLEDGE', 'MIXED'].includes(routeMode.value) ? selectedKnowledgeBaseIds.value : [],
    dataSourceIds: ['AUTO', 'DATABASE', 'MIXED'].includes(routeMode.value) && selectedDataSourceId.value ? [selectedDataSourceId.value] : [],
    ...extra
  };
}

async function submitQuestion(content: string, payloadExtra: Partial<QaMessageSendRequest> = {}, clearManualInput = true) {
  if (!canManageQa.value) return ElMessage.warning(qaManageTip);
  const projectId = projectStore.currentProject?.projectId;
  if (!projectId) return ElMessage.warning(t('请先选择项目'));
  if (!activeSessionId.value) return ElMessage.warning(t('请先新建会话'));
  if (routeMode.value === 'DATABASE' && !selectedDataSourceId.value) return ElMessage.warning(t('数据库问答必须选择一个已启用的数据源'));
  if (routeMode.value === 'MIXED' && selectedDataSourceId.value && !enabledDataSources.value.some((item) => String(item.dataSourceId) === String(selectedDataSourceId.value))) return ElMessage.warning(t('请选择已启用的数据源'));
  if (routeMode.value === 'KNOWLEDGE' && !selectedKnowledgeBaseIds.value.length) return ElMessage.warning(t('知识库问答必须选择至少一个已启用知识库'));
  sending.value = true;
  messageError.value = '';
  const sessionId = activeSessionId.value;
  const userMessage = createLocalUserMessage(sessionId, projectId, content);
  const pendingMessage = createPendingAssistantMessage(sessionId, projectId);
  messages.value = appendQaSubmission(messages.value, userMessage, pendingMessage);
  const sendToken = `${Date.now()}-${Math.random()}`;
  activeSend.value = { token: sendToken, userMessageId: userMessage.messageId, pendingMessageId: pendingMessage.messageId, content };
  if (clearManualInput) question.value = '';
  try {
    const payload = buildQuestionPayload(content, payloadExtra);
    await sendQuestion(sessionId, payload, projectId) as QaMessageExtra;
    if (activeSend.value?.token !== sendToken) return false;
    messages.value = normalizeQaMessages(await fetchQaMessages(sessionId) as QaMessageExtra[]);
    suggestionSubmission.value.submittedKeys = restoreSubmittedSuggestionKeys(messages.value);
    scheduleMessagePolling(sessionId);
    return true;
  } catch (err) {
    if (activeSend.value?.token !== sendToken) return false;
    const detail = err instanceof Error && err.message ? ` ${err.message}` : '';
    const pendingIndex = messages.value.findIndex((msg) => String(msg.messageId) === String(pendingMessage.messageId));
    const errorMessage = createErrorAssistantMessage(sessionId, projectId, `${t('问题发送失败，请检查后端问答接口。')}${detail}`);
    if (pendingIndex >= 0) messages.value.splice(pendingIndex, 1, errorMessage);
    else messages.value.push(errorMessage);
    return false;
  } finally {
    if (activeSend.value?.token === sendToken) {
      activeSend.value = null;
      sending.value = false;
    }
  }
}

async function ask() {
  const content = question.value.trim();
  if (!content) return ElMessage.warning(t('请输入问题'));
  return submitQuestion(content);
}

async function askSuggestedFollowUp(msg: QaMessageExtra, suggestion: string, index: number) {
  const key = suggestionKey(msg, index);
  try {
    await runSuggestedFollowUpSubmission(suggestionSubmission.value, key, async () => {
      const sent = await submitQuestion(suggestion, {
        clientRequestId: `suggestion-${msg.sessionId}-${msg.messageId}-${index}`,
        sourceSuggestionMessageId: msg.messageId
      }, false);
      if (sent !== true) throw new Error('suggestion submission failed');
    });
  } catch {
    // The normal send path already renders a visible failed assistant message.
  }
}

function withdrawCurrentQuestion() {
  if (!activeSend.value) return;
  const current = activeSend.value;
  messages.value = messages.value.filter((msg) => ![current.userMessageId, current.pendingMessageId].some((id) => String(id) === String(msg.messageId)));
  question.value = current.content;
  activeSend.value = null;
  sending.value = false;
  ElMessage.info('已撤回到输入框，可修改后重新发送。');
}

async function feedback(message: QaMessageExtra, useful: boolean) {
  if (!canManageQa.value) return ElMessage.warning(qaManageTip);
  try {
    await submitFeedback(message.messageId, useful);
    feedbackMap.value[String(message.messageId)] = useful;
    ElMessage.success(t('反馈已提交'));
  } catch (err) {
    const detail = err instanceof Error && err.message ? ` ${err.message}` : '';
    ElMessage.error(`${t('反馈提交失败，请检查后端问答反馈接口。')}${detail}`);
  }
}

onMounted(() => loadSessions());
onUnmounted(stopMessagePolling);
</script>

<template>
  <div class="page qa-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">{{ t('知识问答') }}</h2>
        <p class="page-desc">{{ t('支持自动路由、知识库/数据库来源引用、上下文追问和答案反馈。') }}</p>
      </div>
      <el-button v-if="canManageQa" type="primary" :loading="sessionLoading" @click="newSession">{{ t('新建会话') }}</el-button>
    </div>

    <div class="three-col">
      <el-card class="work-card session-card" v-loading="sessionLoading">
        <h3 class="panel-title">{{ t('会话列表') }}</h3>
        <el-alert v-if="sessionError" :title="sessionError" type="error" show-icon :closable="false" style="margin-bottom: 12px" />
        <EmptyState v-if="!sessions.length" :description="t('暂无会话，请新建会话。')" />
        <div v-else class="session-list">
          <div v-for="item in sessions" :key="item.sessionId" class="session-item" :class="{ active: String(activeSessionId) === String(item.sessionId) }" @click="switchSession(item.sessionId)">
            <strong>{{ item.title }} <el-tag v-if="hasSuspiciousText(item.title)" type="warning" size="small">疑似历史乱码数据</el-tag></strong>
            <span>{{ item.updatedAt || item.createdAt }}</span>
            <span v-if="canManageQa" class="session-actions" @click.stop>
              <el-button link type="primary" @click="openRenameSession(item)">改名</el-button>
              <el-button link type="danger" @click="archiveSession(item)">归档</el-button>
            </span>
          </div>
        </div>
      </el-card>

      <el-card class="work-card qa-main" v-loading="messageLoading">
        <h3 class="panel-title">{{ t('对话区') }}{{ activeSession ? ` / ${activeSession.title}` : '' }}</h3>
        <el-alert v-if="messageError" :title="messageError" type="error" show-icon :closable="false" style="margin-bottom: 12px" />
        <el-alert v-if="resourceError" :title="resourceError" type="warning" show-icon :closable="false" style="margin-bottom: 12px" />
        <div class="qa-options" v-loading="resourceLoading">
          <span class="option-label required-label">{{ t('回答方式') }}</span>
          <el-select v-model="routeMode" style="width: 160px">
            <el-option label="自动路由" value="AUTO" />
            <el-option label="模型问答" value="MODEL" />
            <el-option label="知识库问答" value="KNOWLEDGE" />
            <el-option label="数据库问答" value="DATABASE" />
            <el-option label="混合问答" value="MIXED" />
          </el-select>
          <el-select v-if="['AUTO', 'KNOWLEDGE', 'MIXED'].includes(routeMode)" v-model="knowledgeScope" style="width: 150px" aria-label="知识范围">
            <el-option label="项目资料" value="PROJECT" />
            <el-option label="政策资讯" value="POLICY" />
            <el-option label="项目 + 政策" value="ALL" />
          </el-select>
          <el-select v-if="['AUTO', 'KNOWLEDGE', 'MIXED'].includes(routeMode)" v-model="selectedKnowledgeBaseIds" multiple collapse-tags collapse-tags-tooltip style="min-width: 240px" placeholder="当前范围暂无已启用知识库">
            <el-option v-for="item in scopedKnowledgeBases" :key="item.knowledgeBaseId" :label="item.name" :value="item.knowledgeBaseId" />
          </el-select>
          <el-select v-if="['AUTO', 'DATABASE', 'MIXED'].includes(routeMode)" v-model="selectedDataSourceId" clearable style="min-width: 220px" placeholder="选择一个已启用数据源">
            <el-option v-for="item in enabledDataSources" :key="item.dataSourceId" :label="item.name" :value="item.dataSourceId" />
          </el-select>
        </div>
        <div class="message-scroll">
          <EmptyState v-if="!messages.length" :description="t('暂无消息，请输入问题开始问答。')" />
          <div v-for="msg in messages" :key="msg.messageId" class="chat" :class="[messageRole(msg), { pending: msg.pending }]">
            <el-tag v-if="hasSuspiciousText(msg.content || msg.answer)" type="warning" size="small" style="margin-left: 6px">疑似历史乱码数据</el-tag>
            <div v-if="msg.pending && messageRole(msg) === 'assistant'" class="typing-indicator" aria-live="polite"><span></span><span></span><span></span>{{ t('AI 正在生成回答') }}</div>
            <div v-else class="message-body" v-html="renderMessageHtml(msg)"></div>
            <template v-if="messageRole(msg) === 'assistant'">
              <div v-if="messageEvidenceMeta(msg)" class="evidence-recovery" role="status">
                <div class="evidence-status-line">
                  <el-tag :type="messageEvidenceMeta(msg)?.tagType" size="small">{{ messageEvidenceMeta(msg)?.label }}</el-tag>
                  <span>{{ messageEvidenceMeta(msg)?.meaning }}</span>
                </div>
                <p v-if="messageValidityCaution(msg)" class="validity-caution">{{ messageValidityCaution(msg) }}</p>
                <p>{{ EVIDENCE_RECOVERY_PROMPT }}</p>
              </div>
              <p v-else-if="messageValidityCaution(msg)" class="validity-caution standalone-validity-caution">{{ messageValidityCaution(msg) }}</p>
              <div v-if="msg.needClarification || msg.clarificationQuestions?.length" class="clarify-block"><strong>{{ t('需要补充的信息') }}</strong><ul><li v-for="item in msg.clarificationQuestions" :key="item">{{ item }}</li></ul></div>
              <section v-if="messageSuggestions(msg).length" class="suggestion-block" aria-label="延伸问题">
                <strong>{{ t('继续追问') }}</strong>
                <div class="suggestion-list">
                  <el-button
                    v-for="(suggestion, index) in messageSuggestions(msg)"
                    :key="suggestionKey(msg, index)"
                    class="suggestion-button"
                    plain
                    :disabled="sending || suggestionSubmission.pending || suggestionSubmission.submittedKeys.has(suggestionKey(msg, index))"
                    @click="askSuggestedFollowUp(msg, suggestion, index)"
                  >
                    <span>{{ suggestion }}</span>
                    <small v-if="suggestionSubmission.submittedKeys.has(suggestionKey(msg, index))">{{ t('已提问') }}</small>
                  </el-button>
                </div>
              </section>
              <div v-if="!msg.pending" class="feedback">
                <span v-if="feedbackMap[String(msg.messageId)] !== undefined">{{ t('已反馈：') }}{{ feedbackMap[String(msg.messageId)] ? t('有用') : t('无用') }}</span>
                <template v-else>
                  <el-button v-if="canManageQa" size="small" @click="feedback(msg, true)">{{ t('有用') }}</el-button>
                  <el-button v-if="canManageQa" size="small" @click="feedback(msg, false)">{{ t('无用') }}</el-button>
                </template>
                <el-button size="small" @click="openMessageDetail(msg)">详情/引用</el-button>
                <el-button v-if="canManageQa" size="small" type="primary" :loading="String(regeneratingId) === String(msg.messageId)" @click="regenerateAnswer(msg)">重新生成</el-button>
              </div>
            </template>
          </div>
        </div>
        <div class="qa-input-area">
          <div class="input-label required-label">{{ t('问题内容') }}</div>
          <el-input v-model="question" type="textarea" :rows="3" :placeholder="canManageQa ? t('\u8bf7\u8f93\u5165\u95ee\u9898') : qaManageTip" :disabled="!canManageQa || sending" @keyup.ctrl.enter="ask" />
          <div v-if="canManageQa" class="send-actions">
            <el-button type="primary" :loading="sending" :disabled="sending" @click="ask">{{ t('发送') }}</el-button>
            <el-button v-if="sending" @click="withdrawCurrentQuestion">{{ t('撤回编辑') }}</el-button>
          </div>
        </div>
      </el-card>
    </div>
    <el-dialog v-model="renameDialogVisible" title="修改会话标题" width="420px">
      <el-form label-width="90px"><el-form-item label="标题" required><el-input v-model="renameTitle" maxlength="80" show-word-limit /></el-form-item></el-form>
      <template #footer><el-button @click="renameDialogVisible = false">取消</el-button><el-button type="primary" :loading="renaming" @click="submitRenameSession">保存</el-button></template>
    </el-dialog>
    <el-drawer v-model="detailDrawerVisible" title="消息详情与引用" size="560px">
      <div v-loading="detailLoading">
        <EmptyState v-if="!selectedMessage" description="暂无消息详情。" />
        <template v-else>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="消息ID">{{ selectedMessage.messageId }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ selectedMessage.status }}</el-descriptions-item>
            <el-descriptions-item label="路由">{{ selectedMessage.routeMode || '-' }}</el-descriptions-item>
            <el-descriptions-item label="Trace">{{ selectedMessage.providerTraceId || '-' }}</el-descriptions-item>
            <el-descriptions-item label="问题">{{ selectedMessage.question || selectedMessage.content || '-' }}</el-descriptions-item>
            <el-descriptions-item label="回答">{{ selectedMessage.answer || '-' }}</el-descriptions-item>
          </el-descriptions>
          <h3 class="panel-title refs-title">引用来源</h3>
          <EmptyState v-if="!selectedReferences.length" description="暂无单独引用记录。" />
          <pre v-else class="json-block">{{ JSON.stringify(selectedReferences, null, 2) }}</pre>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.qa-page { height: 100%; min-height: 0; overflow: hidden; }
.three-col { flex: 1; min-height: 0; display: grid; grid-template-columns: 280px minmax(0, 1fr); gap: 16px; align-items: stretch; }
.session-card, .qa-main { min-height: 0; overflow: hidden; }
.session-card :deep(.el-card__body), .qa-main :deep(.el-card__body) { height: 100%; min-height: 0; display: flex; flex-direction: column; }
.session-list { flex: 1; min-height: 0; overflow: auto; display: grid; align-content: start; gap: 10px; padding-right: 2px; }
.session-item { text-align: left; border: 1px solid var(--sw-border); border-radius: 10px; background: #fff; padding: 12px; cursor: pointer; display: grid; gap: 6px; }
.session-item.active { border-color: var(--sw-primary); box-shadow: 0 0 0 3px rgba(30, 94, 255, 0.12); }
.session-item span, .muted { color: var(--sw-muted); font-size: 12px; }
.session-actions { display: flex; gap: 8px; }
.message-scroll { flex: 1; min-height: 0; overflow: auto; padding: 4px 8px 4px 0; }
.chat { padding: 12px 14px; border: 1px solid var(--sw-border); border-radius: 12px; margin-bottom: 18px; }
.chat.user { width: fit-content; max-width: min(620px, 62%); margin-left: auto; background: #eaf3ff; border-color: #bfdbfe; text-align: left; }
.chat.assistant { width: min(1040px, calc(100% - 96px)); margin-right: auto; background: #fff; text-align: left; }
.chat.pending { border-color: #bfdbfe; background: #f8fbff; }
.message-body :deep(p) { margin: 8px 0; line-height: 1.75; white-space: normal; }
.message-body :deep(h3), .message-body :deep(h4), .message-body :deep(h5), .message-body :deep(h6) { margin: 12px 0 8px; line-height: 1.45; color: #0f172a; }
.message-body :deep(ul), .message-body :deep(ol) { margin: 8px 0; padding-left: 20px; line-height: 1.7; }
.message-body :deep(code) { padding: 1px 5px; border-radius: 5px; background: #eef2ff; color: #1d4ed8; font-family: Consolas, monospace; }
.message-body :deep(table) { width: 100%; margin: 10px 0; border-collapse: collapse; font-size: 13px; }
.message-body :deep(th), .message-body :deep(td) { border: 1px solid var(--sw-border); padding: 8px 10px; vertical-align: top; }
.message-body :deep(th) { background: #f1f5f9; color: #0f172a; font-weight: 700; }
.message-body :deep(.qa-code) { margin: 10px 0; padding: 10px; overflow: auto; border-radius: 8px; background: #0f172a; color: #e2e8f0; white-space: pre-wrap; }
.message-body :deep(.qa-code code) { padding: 0; background: transparent; color: inherit; }
.message-body :deep(.qa-code span) { color: #93c5fd; }
.typing-indicator { display: inline-flex; align-items: center; gap: 6px; color: var(--sw-muted); font-size: 13px; }
.typing-indicator span { width: 6px; height: 6px; border-radius: 999px; background: var(--sw-primary); animation: qa-typing 1.2s infinite ease-in-out; }
.typing-indicator span:nth-child(2) { animation-delay: 0.15s; }
.typing-indicator span:nth-child(3) { animation-delay: 0.3s; }
@keyframes qa-typing { 0%, 80%, 100% { opacity: 0.35; transform: translateY(0); } 40% { opacity: 1; transform: translateY(-3px); } }
.answer-meta { display: flex; gap: 8px; flex-wrap: wrap; margin: 8px 0; }
.evidence-recovery { margin-top: 10px; padding: 10px 12px; border: 1px solid #fed7aa; border-radius: 10px; background: #fffaf2; color: #7c2d12; font-size: 13px; line-height: 1.55; }
.evidence-status-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.evidence-recovery p { margin: 6px 0 0; color: #9a3412; }
.validity-caution { color: #9a3412; }
.standalone-validity-caution { margin: 10px 0; padding: 8px 10px; border: 1px solid #fed7aa; border-radius: 8px; background: #fffaf2; font-size: 13px; }
.clarify-block { margin-top: 10px; padding: 10px; background: #fff7ed; border: 1px solid #fed7aa; border-radius: 10px; }
.clarify-block ul { margin: 6px 0 0; padding-left: 18px; }
.suggestion-block { margin-top: 14px; padding-top: 12px; border-top: 1px dashed #bfdbfe; }
.suggestion-block > strong { color: #1e3a5f; font-size: 13px; }
.suggestion-list { display: grid; grid-template-columns: minmax(0, 1fr); gap: 8px; margin-top: 8px; }
.suggestion-button { width: fit-content; max-width: 100%; height: auto; min-height: 34px; margin: 0; padding: 8px 12px; border-color: #93c5fd; white-space: normal; text-align: left; }
.suggestion-button span { min-width: 0; overflow-wrap: anywhere; }
.suggestion-button small { flex-shrink: 0; margin-left: 8px; color: var(--sw-muted); }
.reference-block { margin-top: 10px; padding: 10px; background: #f8fafc; border-radius: 10px; }
.source { padding: 8px 0; border-bottom: 1px solid var(--sw-border); }
.feedback { margin-top: 8px; display: flex; gap: 8px; align-items: center; color: var(--sw-muted); }
.qa-options { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.qa-input-area { flex-shrink: 0; padding-top: 10px; border-top: 1px solid var(--sw-border); background: #fff; }
.send-actions { display: flex; gap: 10px; align-items: center; margin-top: 10px; }
.option-label { display: inline-flex; align-items: center; height: 32px; color: var(--sw-muted); font-size: 13px; }
.input-label { margin: 10px 0 8px; font-weight: 700; }
.refs-title { margin-top: 16px; }
.json-block { white-space: pre-wrap; background: #f8fafc; border: 1px solid var(--sw-border); border-radius: 10px; padding: 12px; max-height: 320px; overflow: auto; }
@media (max-width: 960px) { .qa-page { height: auto; overflow: visible; } .three-col { grid-template-columns: 1fr; } .session-card, .qa-main { overflow: visible; } .message-scroll, .session-list { max-height: none; overflow: visible; } .chat.user, .chat.assistant { width: auto; max-width: 100%; } .evidence-status-line { align-items: flex-start; } .suggestion-button { width: 100%; justify-content: space-between; } }
</style>
