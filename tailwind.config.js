/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{svelte,js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        surface: {
          base: '#0C0D10',
          elevated: '#13151A',
          hover: '#1B1E26',
          border: '#262A35',
        },
        text: {
          primary: '#F4F5F8',
          secondary: '#9FA6B7',
          muted: '#7E859C',
        },
        accent: {
          subtle: '#2B121B',
          active: '#D52F58', // Kasane Crimson
          highlight: '#EF5C7D', // Kasane Rose
          wine: '#790F32',
        },
      },
      fontFamily: {
        sans: ['system-ui', '-apple-system', 'sans-serif'],
        mono: ['JetBrains Mono', 'monospace'],
      },
    },
  },
  plugins: [],
};
