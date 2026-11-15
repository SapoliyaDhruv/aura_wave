import React, { useRef, useState, useEffect, useCallback } from 'react';
import { ChevronLeft, ChevronRight, Play } from 'lucide-react';
import { RECOMMENDED_FOR_YOU_ITEMS } from '../data/featuredContent';

export default function RecommendedShelf({ onPlayItem }) {
  const scrollRef = useRef(null);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);

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
  }, [checkScrollBounds]);

  const handleScroll = (dir) => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({
        left: dir === 'left' ? -380 : 380,
        behavior: 'smooth'
      });
      setTimeout(checkScrollBounds, 340);
    }
  };

  return (
    <section className="sp-shelf recommended-shelf" id="home-recommended-shelf">
      <div className="sp-shelf-header">
        <div>
          <div className="shelf-title-with-icon">
            <span className="shelf-icon">🎯</span>
            <h2 className="sp-shelf-title">Recommended For You</h2>
          </div>
          <p className="sp-shelf-subtext">Tuned precisely to your late-night listening frequencies</p>
        </div>

        {/* Carousel arrows */}
        <div className="shelf-nav-arrows">
          <button
            type="button"
            className={`shelf-arrow-btn ${canScrollLeft ? 'active' : 'disabled'}`}
            onClick={() => handleScroll('left')}
            disabled={!canScrollLeft}
            title="Scroll left"
            aria-label="Scroll left"
          >
            <ChevronLeft size={18} />
          </button>
          <button
            type="button"
            className={`shelf-arrow-btn ${canScrollRight ? 'active' : 'disabled'}`}
            onClick={() => handleScroll('right')}
            disabled={!canScrollRight}
            title="Scroll right"
            aria-label="Scroll right"
          >
            <ChevronRight size={18} />
          </button>
        </div>
      </div>

      <div className="sp-cards-row recommended-scroll-row" ref={scrollRef}>
        {RECOMMENDED_FOR_YOU_ITEMS.map((item) => (
          <div
            key={item.id}
            className="sp-card recommended-card"
            onClick={() => onPlayItem && onPlayItem(item)}
          >
            <div className="sp-card-cover-wrap">
              <img src={item.cover} alt={item.title} className="sp-card-cover" />
              {/* Format Badge Top Pill */}
              <div className="rec-card-format-badge">
                <span>{item.badge}</span>
              </div>
              <div className="sp-card-hover-play">
                <Play size={22} fill="#000000" color="#000000" style={{ marginLeft: '2px' }} />
              </div>
            </div>
            <span className="sp-card-title">{item.title}</span>
            <span className="sp-card-desc">{item.subtitle}</span>
          </div>
        ))}
      </div>
    </section>
  );
}
