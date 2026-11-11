/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{svelte,js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        surface: {
          base: '#111215',
          elevated: '#17181D',
          hover: '#202228',
          border: '#282A32',
        },
        text: {
          primary: '#EDEDEF',
          secondary: '#8F93A0',
          muted: '#5A5E6B',
        },
        accent: {
          subtle: '#2B121B',
          active: '#D52F58', // Kasane Crimson
          highlight: '#EF5C7D', // Kasane Rose
        },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'sans-serif'],
        mono: ['JetBrains Mono', 'monospace'],
      },
    },
  },
  plugins: [],
};
