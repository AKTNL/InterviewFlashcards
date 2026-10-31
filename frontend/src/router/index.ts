import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: () => import('../views/HomeView.vue') },
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
    { path: '/sources', name: 'sources', meta: { requiresAuth: true }, component: () => import('../views/SourcesView.vue') },
    { path: '/settings', name: 'settings', meta: { requiresAuth: true }, component: () => import('../views/SettingsView.vue') },
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
  if (!to.meta.requiresAuth) return
  // The page offers retry on a connection error; only a confirmed 401 redirects to login.
  try { await auth.refresh() } catch { return }
  if (!auth.user) return { name: 'login' }
})
