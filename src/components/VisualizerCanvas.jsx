import React, { useEffect, useRef } from 'react';
import { audioEngine } from '../services/audioEngine';

export default function VisualizerCanvas({ isPlaying, primaryColor = '#1DB954', secondaryColor = '#8b5cf6', height = 60, barCount = 32 }) {
  const canvasRef = useRef(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    let animId;

    const render = () => {
      const width = canvas.width;
      const height = canvas.height;
      ctx.clearRect(0, 0, width, height);

      const freqData = audioEngine.getFrequencyData();
      const step = width / barCount;
      const barWidth = Math.max(3, step * 0.65);

      const gradient = ctx.createLinearGradient(0, height, 0, 0);
      gradient.addColorStop(0, primaryColor);
      gradient.addColorStop(1, secondaryColor);

      for (let i = 0; i < barCount; i++) {
        const dataIndex = Math.floor((i / barCount) * (freqData.length || 32));
        let val = freqData[dataIndex] || 0;

        if (!isPlaying) {
          val = 12 + 6 * Math.sin(Date.now() * 0.003 + i * 0.3);
        }

        const barHeight = Math.max(4, (val / 255) * height * 0.95);
        const x = i * step + (step - barWidth) / 2;
        const y = height - barHeight;

        ctx.fillStyle = gradient;
        ctx.beginPath();
        // Rounded top bars
        const radius = Math.min(barWidth / 2, 4);
        ctx.roundRect(x, y, barWidth, barHeight, [radius, radius, 0, 0]);
        ctx.fill();

        // Subtle glow effect
        if (isPlaying && val > 160) {
          ctx.shadowColor = primaryColor;
          ctx.shadowBlur = 8;
        } else {
          ctx.shadowBlur = 0;
        }
      }

      animId = requestAnimationFrame(render);
    };

    render();

    return () => {
      cancelAnimationFrame(animId);
    };
  }, [isPlaying, primaryColor, secondaryColor, barCount]);

  return (
    <canvas
      ref={canvasRef}
      width={360}
      height={height}
      className="visualizer-canvas"
      id="audio-visualizer-canvas"
    />
  );
}
