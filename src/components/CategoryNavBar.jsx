import React, { useRef, useState, useEffect, useCallback } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';

export default function CategoryNavBar({
  categories = [],
  activeCategory = 'all',
  onSelectCategory,
  className = ''
}) {
  const scrollRef = useRef(null);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(false);
  const isDraggingRef = useRef(false);
  const startXRef = useRef(0);
  const startScrollLeftRef = useRef(0);
  const hasMovedRef = useRef(false);

  // Check scroll boundary status
  const updateScrollBounds = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const { scrollLeft, scrollWidth, clientWidth } = el;
    setCanScrollLeft(scrollLeft > 6);
    setCanScrollRight(scrollLeft < scrollWidth - clientWidth - 6);
  }, []);

  // Update bounds on mount, resize, and scroll
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;

    updateScrollBounds();
    el.addEventListener('scroll', updateScrollBounds, { passive: true });
    window.addEventListener('resize', updateScrollBounds);

    return () => {
      el.removeEventListener('scroll', updateScrollBounds);
      window.removeEventListener('resize', updateScrollBounds);
    };
  }, [updateScrollBounds, categories]);

  // Translate vertical mouse wheel to horizontal scrolling smoothly
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;

    const handleWheel = (e) => {
      if (Math.abs(e.deltaY) > Math.abs(e.deltaX) && e.deltaY !== 0) {
        if (el.scrollWidth > el.clientWidth) {
          e.preventDefault();
          el.scrollLeft += e.deltaY * 0.9;
          updateScrollBounds();
        }
      }
    };

    el.addEventListener('wheel', handleWheel, { passive: false });
    return () => {
      el.removeEventListener('wheel', handleWheel);
    };
  }, [updateScrollBounds]);

  // Smooth scroll left / right arrows
  const handleScrollBy = (amount) => {
    const el = scrollRef.current;
    if (!el) return;
    el.scrollBy({ left: amount, behavior: 'smooth' });
    setTimeout(updateScrollBounds, 320);
  };

  // Mouse Drag-to-Scroll handlers
  const handleMouseDown = (e) => {
    const el = scrollRef.current;
    if (!el) return;
    isDraggingRef.current = true;
    hasMovedRef.current = false;
    startXRef.current = e.pageX - el.offsetLeft;
    startScrollLeftRef.current = el.scrollLeft;
  };

  const handleMouseMove = (e) => {
    if (!isDraggingRef.current) return;
    const el = scrollRef.current;
    if (!el) return;
    const x = e.pageX - el.offsetLeft;
    const walk = (x - startXRef.current) * 1.3;
    if (Math.abs(walk) > 4) {
      hasMovedRef.current = true;
    }
    el.scrollLeft = startScrollLeftRef.current - walk;
    updateScrollBounds();
  };

  const handleMouseUpOrLeave = () => {
    isDraggingRef.current = false;
  };

  const handlePillClick = (e, catId) => {
    if (hasMovedRef.current) {
      e.preventDefault();
      e.stopPropagation();
      return;
    }
    onSelectCategory(catId);

    // Auto-scroll the clicked pill into center view
    const pill = e.currentTarget;
    if (pill && pill.scrollIntoView) {
      pill.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'nearest' });
    }
  };

  return (
    <div className={`sp-category-nav-wrapper ${className}`} id="home-category-nav-wrapper">
      {/* Permanent Left Scroll Navigation Button */}
      <button
        type="button"
        className={`sp-cat-nav-arrow-btn left ${canScrollLeft ? 'active' : 'disabled'}`}
        onClick={() => handleScrollBy(-260)}
        disabled={!canScrollLeft}
        title="Scroll categories left"
        aria-label="Scroll left"
      >
        <ChevronLeft size={18} />
      </button>

      {/* Scrollable Pills Track (Hidden Scrollbar) */}
      <div
        className="sp-category-nav-bar"
        id="home-category-nav-bar"
        ref={scrollRef}
        onMouseDown={handleMouseDown}
        onMouseMove={handleMouseMove}
        onMouseUp={handleMouseUpOrLeave}
        onMouseLeave={handleMouseUpOrLeave}
      >
        {categories.map((cat) => {
          const isActive = activeCategory === cat.id;
          return (
            <button
              key={cat.id}
              type="button"
              className={`sp-category-nav-pill ${isActive ? 'active' : ''}`}
              onClick={(e) => handlePillClick(e, cat.id)}
              id={`cat-nav-${cat.id}`}
              aria-pressed={isActive}
            >
              {cat.label}
            </button>
          );
        })}
      </div>

      {/* Permanent Right Scroll Navigation Button */}
      <button
        type="button"
        className={`sp-cat-nav-arrow-btn right ${canScrollRight ? 'active' : 'disabled'}`}
        onClick={() => handleScrollBy(260)}
        disabled={!canScrollRight}
        title="Scroll categories right"
        aria-label="Scroll right"
      >
        <ChevronRight size={18} />
      </button>
    </div>
  );
}
