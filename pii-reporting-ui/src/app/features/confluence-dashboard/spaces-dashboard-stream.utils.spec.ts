import { formatEventLog, isAttachmentPayload, coerceSpaceKey } from './spaces-dashboard-stream.utils';

describe('spaces-dashboard-stream.utils', () => {

  // ========== formatEventLog ==========

  describe('formatEventLog', () => {
    it('Should_FormatStartEvent_When_Start', () => {
      const result = formatEventLog('start', JSON.stringify({ spaceKey: 'SPACE1', pagesTotal: 42 }));
      expect(result).toBe('[start] space=SPACE1 pagesTotal=42');
    });

    it('Should_FormatStartWithoutTotal_When_NoPagesTotal', () => {
      const result = formatEventLog('start', JSON.stringify({ spaceKey: 'SPACE1' }));
      expect(result).toBe('[start] space=SPACE1');
    });

    it('Should_FormatPageStart_When_PageStart', () => {
      const result = formatEventLog('pageStart', JSON.stringify({
        spaceKey: 'SPACE1', pageIndex: 3, pagesTotal: 10, pageTitle: 'My Page'
      }));
      expect(result).toContain('[page_start]');
      expect(result).toContain('space=SPACE1');
      expect(result).toContain('3/10');
      expect(result).toContain('My Page');
    });

    it('Should_FormatItem_When_Item', () => {
      const result = formatEventLog('item', JSON.stringify({
        spaceKey: 'SPACE1', entities: [1, 2, 3]
      }));
      expect(result).toBe('[item] space=SPACE1 3 entities');
    });

    it('Should_FormatAttachmentItem_When_AttachmentItem', () => {
      const result = formatEventLog('attachmentItem', JSON.stringify({
        spaceKey: 'SPACE1', attachmentName: 'file.pdf', attachmentType: 'application/pdf'
      }));
      expect(result).toContain('[attachmentItem]');
      expect(result).toContain('file.pdf');
      expect(result).toContain('(application/pdf)');
    });

    it('Should_FormatComplete_When_Complete', () => {
      const result = formatEventLog('complete', JSON.stringify({ spaceKey: 'SPACE1' }));
      expect(result).toBe('[complete] space=SPACE1');
    });

    it('Should_FormatMultiEvents_When_MultiStartOrComplete', () => {
      expect(formatEventLog('multiStart', '{}')).toBe('[multiStart]');
      expect(formatEventLog('multiComplete', '{}')).toBe('[multiComplete]');
    });

    it('Should_FallbackToRaw_When_UnknownType', () => {
      const result = formatEventLog('keepalive', '{"ping":true}');
      expect(result).toBe('[keepalive] {"ping":true}');
    });

    it('Should_HandleInvalidJson_When_ParseFails', () => {
      const result = formatEventLog('start', 'not-json');
      expect(result).toBe('[start] not-json');
    });
  });

  // ========== isAttachmentPayload ==========

  describe('isAttachmentPayload', () => {
    it('Should_ReturnTrue_When_HasAttachmentName', () => {
      expect(isAttachmentPayload({ attachmentName: 'file.pdf' } as any)).toBe(true);
    });

    it('Should_ReturnTrue_When_HasAttachmentUrl', () => {
      expect(isAttachmentPayload({ attachmentUrl: 'https://example.com/file.pdf' } as any)).toBe(true);
    });

    it('Should_ReturnFalse_When_NoAttachmentInfo', () => {
      expect(isAttachmentPayload({ pageId: '123' } as any)).toBe(false);
    });

    it('Should_ReturnFalse_When_NullOrUndefined', () => {
      expect(isAttachmentPayload(null)).toBe(false);
      expect(isAttachmentPayload(undefined)).toBe(false);
    });
  });

  // ========== coerceSpaceKey ==========

  describe('coerceSpaceKey', () => {
    it('Should_ReturnTrimmedKey_When_ValidSpaceKey', () => {
      expect(coerceSpaceKey({ spaceKey: '  SPACE1  ' } as any)).toBe('SPACE1');
    });

    it('Should_ReturnNull_When_EmptyOrWhitespace', () => {
      expect(coerceSpaceKey({ spaceKey: '   ' } as any)).toBeNull();
      expect(coerceSpaceKey({ spaceKey: '' } as any)).toBeNull();
    });

    it('Should_ReturnNull_When_NullOrUndefined', () => {
      expect(coerceSpaceKey(null)).toBeNull();
      expect(coerceSpaceKey(undefined)).toBeNull();
      expect(coerceSpaceKey({} as any)).toBeNull();
    });
  });
});
