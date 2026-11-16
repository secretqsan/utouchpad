<script setup>
import AppIcon from "./AppIcon.vue";

defineProps({
  current: { type: String, default: "pairing" },
});

const emit = defineEmits(["navigate"]);

const items = [
  { id: "pairing", label: "配对手机", icon: "link" },
  { id: "password", label: "Windows 密码", icon: "key" },
  { id: "maintenance", label: "维护", icon: "wrench" },
  { id: "about", label: "关于", icon: "info" },
];
</script>

<template>
  <aside class="h-full px-5 pt-4 pb-5">
    <nav class="flex flex-col gap-1.5" aria-label="主导航">
      <button
        v-for="item in items"
        :key="item.id"
        type="button"
        class="flex w-full items-center gap-3 rounded-lg px-3.5 py-2.5 text-left text-sm font-medium transition"
        :class="
          current === item.id
            ? 'bg-panel text-accent shadow-neo-inset'
            : 'bg-panel text-muted shadow-neo-sm hover:text-ink'
        "
        :aria-current="current === item.id ? 'page' : undefined"
        @click="emit('navigate', item.id)"
      >
        <AppIcon :name="item.icon" :size="18" class="shrink-0" />
        <span>{{ item.label }}</span>
      </button>
    </nav>
  </aside>
</template>
