import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/decks', name: 'decks', meta: { requiresAuth: true }, component: () => import('../views/DecksView.vue') },
    { path: '/review-parameters', name: 'review-parameters', meta: { requiresAuth: true }, component: () => import('../views/ReviewParametersView.vue') },
    { path: '/trends', name: 'trends', meta: { requiresAuth: true }, component: () => import('../views/TrendsView.vue') },
    { path: '/', name: 'home', component: () => import('../views/HomeView.vue') },
    { path: '/library', name: 'library', component: () => import('../views/LibraryView.vue') },
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
    { path: '/register', name: 'register', component: () => import('../views/RegisterView.vue') },
    { path: '/admin', name: 'admin', meta: { requiresAuth: true, requiresAdmin: true }, component: () => import('../views/AdminView.vue') },
    { path: '/account', name: 'account', meta: { requiresAuth: true }, component: () => import('../views/AccountView.vue') },
    { path: '/sources', name: 'sources', meta: { requiresAuth: true }, component: () => import('../views/SourcesView.vue') },
    { path: '/settings', name: 'settings', meta: { requiresAuth: true }, component: () => import('../views/SettingsView.vue') },
    { path: '/data', name: 'data', meta: { requiresAuth: true }, component: () => import('../views/DataView.vue') },
    { path: '/backups', name: 'backups', meta: { requiresAuth: true }, component: () => import('../views/BackupsView.vue') },
    { path: '/shares', name: 'shares', meta: { requiresAuth: true }, component: () => import('../views/SharesView.vue') },
    { path: '/groups', name: 'groups', meta: { requiresAuth: true }, component: () => import('../views/GroupsView.vue') },
    { path: '/imports/:id', name: 'import', meta: { requiresAuth: true }, component: () => import('../views/ImportView.vue') },
    { path: '/chapters/:id/cards', name: 'chapter-cards', meta: { requiresAuth: true }, component: () => import('../views/CardsView.vue') },
    { path: '/cards', name: 'cards', meta: { requiresAuth: true }, component: () => import('../views/CardsView.vue') },
    { path: '/practice/:id?', name: 'practice', meta: { requiresAuth: true }, component: () => import('../views/PracticeView.vue') },
    { path: '/cards/:id', name: 'card-detail', meta: { requiresAuth: true }, component: () => import('../views/CardDetailView.vue') },
    { path: '/sources/:id/read', name: 'source-reader', meta: { requiresAuth: true }, component: () => import('../views/SourceReaderView.vue') },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFoundView.vue') },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async to => {
  const auth = useAuthStore()
  if (!to.meta.requiresAuth) {
    if (auth.user?.mustChangePassword && !['login', 'register'].includes(String(to.name))) return { name: 'account' }
    return
  }
  // The page offers retry on a connection error; only a confirmed 401 redirects to login.
  try { await auth.refresh() } catch { if (to.meta.requiresAdmin) return false; return }
  if (!auth.user) return { name: 'login' }
  if (auth.user.mustChangePassword && to.name !== 'account') return { name: 'account' }
  if (to.meta.requiresAdmin && !auth.user.capabilities?.includes('users.manage')) return { name: 'account' }
})
