<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ChatLineRound, Coin, DocumentChecked, Files, FolderOpened, House, Key, Menu, Notebook, Operation, Picture, Reading, Setting, SwitchButton, Tickets, User } from '@element-plus/icons-vue';
import { changeCurrentPassword } from '../api/auth';
import { useProjectStore } from '../stores/project';
import { useUserStore } from '../stores/user';

const route = useRoute();
const router = useRouter();
const projectStore = useProjectStore();
const userStore = useUserStore();
const passwordDialogVisible = ref(false);
const passwordSaving = ref(false);
const mobileDrawerVisible = ref(false);
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' });

const menuGroups = [
  {
    key: 'home',
    title: '工作入口',
    children: [
      { path: '/dashboard', title: '工作台', icon: House, permission: 'dashboard:view' }
    ]
  },
  {
    key: 'ai',
    title: '智能应用',
    children: [
      { path: '/qa', title: '智能问答', icon: ChatLineRound, permission: 'qa:view' },
      { path: '/review', title: '合规审查', icon: DocumentChecked, permission: 'review:view' },
      { path: '/report', title: '报告生成', icon: Files, permission: 'report:view' },
      { path: '/ocr', title: 'OCR识别', icon: Picture, permission: 'ocr:view' }
    ]
  },
  {
    key: 'assets',
    title: '知识资产',
    children: [
      { path: '/knowledge', title: '项目知识库', icon: Notebook, permission: 'knowledge:view' },
      { path: '/policy', title: '政策资讯', icon: Reading, permission: 'knowledge:view' },
      { path: '/datasources', title: '数据源管理', icon: Coin }
    ]
  },
  {
    key: 'operations',
    title: '运营中心',
    children: [
      { path: '/tasks', title: '任务中心', icon: Operation },
      { path: '/audit', title: '审计日志', icon: Setting }
    ]
  },
  {
    key: 'config',
    title: '基础配置',
    children: [
      { path: '/projects', title: '项目管理', icon: FolderOpened, permission: 'project:view' },
      { path: '/templates', title: '模板管理', icon: Tickets, permission: 'template:view' },
      { path: '/system/users', title: '用户管理', icon: User, permission: 'system:user:manage' },
      { path: '/system/roles', title: '角色管理', icon: Setting, permission: 'system:user:manage' }
    ]
  }
];

const visibleMenuGroups = computed(() => menuGroups
  .map((group) => ({ ...group, children: group.children.filter((item) => userStore.hasPermission(item.permission)) }))
  .filter((group) => group.children.length));
const activeMenu = computed(() => route.path.startsWith('/report') ? '/report' : route.path);
const currentProject = computed(() => projectStore.currentProject);

onMounted(async () => {
  if (!userStore.user && userStore.token) await userStore.fetchCurrentUser();
  if (!projectStore.projects.length) await projectStore.fetchProjects();
});

async function handleMenuSelect(path: string) {
  mobileDrawerVisible.value = false;
  if (path !== activeMenu.value) await router.push(path);
}

async function logout() {
  await ElMessageBox.confirm('确认退出当前账号？', '退出登录', { type: 'warning' });
  await userStore.logout();
  router.replace('/login');
}

function openPasswordDialog() {
  Object.assign(passwordForm, { oldPassword: '', newPassword: '', confirmPassword: '' });
  passwordDialogVisible.value = true;
}

async function submitPasswordChange() {
  if (!passwordForm.oldPassword) return ElMessage.warning('请输入原密码');
  if (passwordForm.newPassword.length < 6) return ElMessage.warning('新密码至少 6 位');
  if (passwordForm.newPassword !== passwordForm.confirmPassword) return ElMessage.warning('两次新密码不一致');
  passwordSaving.value = true;
  try {
    await changeCurrentPassword({ oldPassword: passwordForm.oldPassword, newPassword: passwordForm.newPassword });
    ElMessage.success('密码已修改，请重新登录');
    passwordDialogVisible.value = false;
    await userStore.logout();
    router.replace('/login');
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '修改密码失败');
  } finally {
    passwordSaving.value = false;
  }
}
</script>

<template>
  <el-container class="main-layout">
    <el-aside width="236px" class="sidebar desktop-sidebar">
      <div class="brand">
        <div class="brand-mark">AI</div>
        <div><strong>智慧工地</strong><span>大模型应用系统</span></div>
      </div>
      <el-menu :default-active="activeMenu" router class="side-menu" :default-openeds="['ai', 'assets']">
        <template v-for="group in visibleMenuGroups" :key="group.key">
          <el-menu-item v-if="group.children.length === 1" :index="group.children[0].path">
            <el-icon><component :is="group.children[0].icon" /></el-icon>
            <span>{{ group.children[0].title }}</span>
          </el-menu-item>
          <el-sub-menu v-else :index="group.key">
            <template #title>
              <span class="menu-group-title">{{ group.title }}</span>
            </template>
            <el-menu-item v-for="item in group.children" :key="item.path" :index="item.path">
              <el-icon><component :is="item.icon" /></el-icon>
              <span>{{ item.title }}</span>
            </el-menu-item>
          </el-sub-menu>
        </template>
      </el-menu>
    </el-aside>
    <el-container class="main-shell">
      <el-header class="topbar" height="64px">
        <div class="project-heading">
          <el-button class="mobile-menu-button" circle aria-label="打开导航菜单" @click="mobileDrawerVisible = true">
            <el-icon><Menu /></el-icon>
          </el-button>
          <div class="project-heading-copy">
            <div class="current-project">当前项目：{{ currentProject?.name || currentProject?.projectName || '未选择' }}</div>
            <div class="project-meta">{{ currentProject?.code || currentProject?.projectCode || '-' }} / {{ currentProject?.address || currentProject?.location || '-' }}</div>
          </div>
        </div>
        <div class="top-actions">
          <el-button v-if="route.path !== '/qa'" class="ask-button" type="primary" plain @click="router.push('/qa')">去提问</el-button>
          <el-select v-model="projectStore.currentProjectId" class="project-select" :loading="projectStore.loading" @change="projectStore.switchProject">
            <el-option v-for="project in projectStore.projects" :key="project.projectId" :label="project.name || project.projectName" :value="String(project.projectId)" :disabled="!['ACTIVE', 'ENABLED'].includes(project.status)" />
          </el-select>
          <el-dropdown>
            <span class="user-chip">{{ userStore.displayName }} / {{ userStore.roles[0] || '未分配角色' }}</span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item :icon="Key" @click="openPasswordDialog">修改密码</el-dropdown-item>
                <el-dropdown-item :icon="SwitchButton" @click="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="content"><router-view :key="projectStore.currentProjectId" /></el-main>
    </el-container>
    <el-drawer
      v-model="mobileDrawerVisible"
      class="mobile-nav-drawer"
      direction="ltr"
      title="主导航"
      size="min(320px, 88vw)"
      :with-header="false"
    >
      <nav class="mobile-navigation" aria-label="主导航">
        <div class="brand">
          <div class="brand-mark">AI</div>
          <div><strong>智慧工地</strong><span>大模型应用系统</span></div>
        </div>
        <el-menu :default-active="activeMenu" class="side-menu" :default-openeds="['ai', 'assets']" @select="handleMenuSelect">
          <template v-for="group in visibleMenuGroups" :key="group.key">
            <el-menu-item v-if="group.children.length === 1" :index="group.children[0].path">
              <el-icon><component :is="group.children[0].icon" /></el-icon>
              <span>{{ group.children[0].title }}</span>
            </el-menu-item>
            <el-sub-menu v-else :index="group.key">
              <template #title><span class="menu-group-title">{{ group.title }}</span></template>
              <el-menu-item v-for="item in group.children" :key="item.path" :index="item.path">
                <el-icon><component :is="item.icon" /></el-icon>
                <span>{{ item.title }}</span>
              </el-menu-item>
            </el-sub-menu>
          </template>
        </el-menu>
      </nav>
    </el-drawer>
    <el-dialog v-model="passwordDialogVisible" title="修改当前用户密码" width="420px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="原密码" required><el-input v-model="passwordForm.oldPassword" type="password" show-password autocomplete="current-password" /></el-form-item>
        <el-form-item label="新密码" required><el-input v-model="passwordForm.newPassword" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-form-item label="确认密码" required><el-input v-model="passwordForm.confirmPassword" type="password" show-password autocomplete="new-password" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="passwordSaving" @click="submitPasswordChange">保存</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<style scoped>
.main-layout { width: 100%; height: 100vh; background: var(--sw-bg); overflow: hidden; }
.main-shell { min-width: 0; height: 100vh; overflow: hidden; }
.sidebar { height: 100vh; background: linear-gradient(180deg, #0f2f63 0%, #133a74 58%, #0f766e 135%); color: #fff; display: flex; flex-direction: column; overflow: hidden; }
.brand { height: 64px; display: flex; align-items: center; gap: 12px; padding: 0 18px; border-bottom: 1px solid rgba(255,255,255,0.12); }
.brand-mark { width: 38px; height: 38px; display: grid; place-items: center; border-radius: 12px; background: linear-gradient(135deg, #1e5eff, #0f766e); font-weight: 800; }
.brand span { display: block; margin-top: 3px; font-size: 12px; color: rgba(255,255,255,0.72); }
.side-menu { flex: 1; min-height: 0; overflow-y: auto; border-right: 0; background: transparent; }
.side-menu::-webkit-scrollbar { width: 6px; }
.side-menu::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.28); border-radius: 999px; }
.side-menu :deep(.el-menu-item),
.side-menu :deep(.el-sub-menu__title) { color: rgba(255,255,255,0.82); margin: 6px 10px; border-radius: 10px; }
.side-menu :deep(.el-sub-menu__title:hover),
.side-menu :deep(.el-menu-item:hover) { background: rgba(255,255,255,0.1); color: #fff; }
.side-menu :deep(.el-menu-item.is-active) { background: rgba(255,255,255,0.14); color: #fff; }
.side-menu :deep(.el-sub-menu .el-menu-item) { min-width: 0; margin-left: 18px; }
.side-menu :deep(.el-menu) { background: transparent; }
.menu-group-title { font-size: 13px; font-weight: 700; letter-spacing: 0.08em; color: rgba(255,255,255,0.68); }
.topbar { flex-shrink: 0; display: flex; align-items: center; justify-content: space-between; background: #fff; border-bottom: 1px solid var(--sw-border); }
.project-heading { min-width: 0; display: flex; align-items: center; gap: 10px; }
.project-heading-copy { min-width: 0; }
.current-project { font-weight: 700; }
.project-meta { margin-top: 4px; color: var(--sw-muted); font-size: 12px; }
.top-actions { display: flex; align-items: center; gap: 14px; }
.mobile-menu-button { display: none; flex-shrink: 0; }
.project-select { width: 240px; }
.user-chip { cursor: pointer; padding: 8px 12px; border: 1px solid var(--sw-border); border-radius: 999px; }
.content { min-width: 0; height: calc(100vh - 64px); padding: 20px; overflow: auto; }
.mobile-navigation { height: 100%; display: flex; flex-direction: column; background: linear-gradient(180deg, #0f2f63 0%, #133a74 58%, #0f766e 135%); color: #fff; }
:global(.mobile-nav-drawer .el-drawer__body) { padding: 0; }

@media (max-width: 960px) {
  .desktop-sidebar { display: none; }
  .mobile-menu-button { display: inline-flex; }
  .main-shell { width: 100%; max-width: 100%; }
  .topbar { height: auto !important; min-height: 64px; padding: 10px 12px; flex-wrap: wrap; align-content: center; gap: 10px; }
  .project-heading { flex: 1 1 100%; }
  .project-meta { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .top-actions { width: 100%; min-width: 0; gap: 8px; }
  .project-select { width: 100%; min-width: 0; flex: 1 1 auto; }
  .ask-button { display: none; }
  .user-chip { display: block; max-width: 132px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .content { min-width: 0; height: auto; padding: 12px; overflow-x: hidden; }
}
</style>
