<script setup>
defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 20 },
});
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <g v-if="name === 'link'">
      <path d="M10 13a5 5 0 0 1 0-7l1-1a5 5 0 0 1 7 7l-1 1" />
      <path d="M14 11a5 5 0 0 1 0 7l-1 1a5 5 0 0 1-7-7l1-1" />
    </g>
    <g v-else-if="name === 'key'">
      <circle cx="8" cy="12" r="3" />
      <path d="M11 12h9" />
      <path d="M17 12v3" />
      <path d="M20 12v2" />
    </g>
    <g v-else-if="name === 'wrench'">
      <path d="M14.5 6.5a4.5 4.5 0 0 0-6 6L3 18l3 3 5.5-5.5a4.5 4.5 0 0 0 6-6l-3 3-3-3 3-3z" />
    </g>
    <g v-else-if="name === 'refresh' || name === 'restart'">
      <path d="M20 11a8 8 0 1 0-2.3 6.7" />
      <path d="M20 5v6h-6" />
    </g>
    <g v-else-if="name === 'copy'">
      <rect x="9" y="9" width="10" height="10" rx="2" />
      <path d="M5 15H4V4h11v1" />
    </g>
    <g v-else-if="name === 'qr'">
      <rect x="4" y="4" width="6" height="6" rx="1" />
      <rect x="14" y="4" width="6" height="6" rx="1" />
      <rect x="4" y="14" width="6" height="6" rx="1" />
      <path d="M14 14h2v2h-2z" />
      <path d="M18 18h2v2h-2z" />
    </g>
    <g v-else-if="name === 'warning'">
      <path d="M12 4 21 20H3z" />
      <path d="M12 10v4" />
      <path d="M12 17h.01" />
    </g>
    <g v-else-if="name === 'info'">
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11v5" />
      <path d="M12 8h.01" />
    </g>
    <g v-else-if="name === 'close'">
      <path d="M6 6l12 12" />
      <path d="M18 6 6 18" />
    </g>
    <g v-else-if="name === 'trash'">
      <path d="M4 7h16" />
      <path d="M9 7V5h6v2" />
      <path d="M6 7l1 13h10l1-13" />
      <path d="M10 11v6" />
      <path d="M14 11v6" />
    </g>
  </svg>
</template>
