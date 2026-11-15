import React, { useRef, useState, useEffect, useCallback } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';

export default function ScrollableShelfRow({
  children,
  className = '',
  id,
  scrollDistance = 380,
  showArrows = true,
  arrowsPosition = 'floating' // 'floating' | 'none'
}) {
  const scrollRef = useRef(null);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(false);
  const isDraggingRef = useRef(false);
  const startXRef = useRef(0);
  const startScrollLeftRef = useRef(0);

  const checkScrollBounds = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const { scrollLeft, scrollWidth, clientWidth } = el;
    setCanScrollLeft(scrollLeft > 10);
    setCanScrollRight(scrollLeft < scrollWidth - clientWidth - 10);
  }, []);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;

    checkScrollBounds();
    el.addEventListener('scroll', checkScrollBounds, { passive: true });
    window.addEventListener('resize', checkScrollBounds);

    // Translate mouse wheel deltaY to deltaX
    const handleWheel = (e) => {
      if (Math.abs(e.deltaY) > Math.abs(e.deltaX) && e.deltaY !== 0) {
        if (el.scrollWidth > el.clientWidth) {
          e.preventDefault();
          el.scrollLeft += e.deltaY * 0.9;
          checkScrollBounds();
        }
      }
    };

    el.addEventListener('wheel', handleWheel, { passive: false });

    return () => {
      el.removeEventListener('scroll', checkScrollBounds);
      el.removeEventListener('wheel', handleWheel);
      window.removeEventListener('resize', checkScrollBounds);
    };
  }, [checkScrollBounds, children]);

  const handleScroll = (direction) => {
    const el = scrollRef.current;
    if (!el) return;
    const distance = direction === 'left' ? -scrollDistance : scrollDistance;
    el.scrollBy({ left: distance, behavior: 'smooth' });
    setTimeout(checkScrollBounds, 320);
  };

  // Drag-to-scroll handlers
  const handleMouseDown = (e) => {
    const el = scrollRef.current;
    if (!el) return;
    isDraggingRef.current = true;
    startXRef.current = e.pageX - el.offsetLeft;
    startScrollLeftRef.current = el.scrollLeft;
  };

  const handleMouseMove = (e) => {
    if (!isDraggingRef.current) return;
    const el = scrollRef.current;
    if (!el) return;
    const x = e.pageX - el.offsetLeft;
    const walk = (x - startXRef.current) * 1.25;
    el.scrollLeft = startScrollLeftRef.current - walk;
    checkScrollBounds();
  };

  const handleMouseUpOrLeave = () => {
    isDraggingRef.current = false;
  };

  return (
    <div className="scrollable-shelf-wrapper">
      {/* Floating Left Arrow */}
      {showArrows && arrowsPosition === 'floating' && (
        <button
          type="button"
          className={`shelf-floating-nav-btn left ${canScrollLeft ? 'visible' : 'hidden'}`}
          onClick={() => handleScroll('left')}
          disabled={!canScrollLeft}
          title="Scroll left"
          aria-label="Scroll left"
        >
          <ChevronLeft size={20} />
        </button>
      )}

      {/* Cards Container */}
      <div
        className={`sp-cards-row ${className}`}
        id={id}
        ref={scrollRef}
        onMouseDown={handleMouseDown}
        onMouseMove={handleMouseMove}
        onMouseUp={handleMouseUpOrLeave}
        onMouseLeave={handleMouseUpOrLeave}
      >
        {children}
      </div>

      {/* Floating Right Arrow */}
      {showArrows && arrowsPosition === 'floating' && (
        <button
          type="button"
          className={`shelf-floating-nav-btn right ${canScrollRight ? 'visible' : 'hidden'}`}
          onClick={() => handleScroll('right')}
          disabled={!canScrollRight}
          title="Scroll right"
          aria-label="Scroll right"
        >
          <ChevronRight size={20} />
        </button>
      )}
    </div>
  );
}
