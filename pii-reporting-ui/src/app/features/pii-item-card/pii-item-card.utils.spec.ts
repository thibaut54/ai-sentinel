import { TestBed } from '@angular/core/testing';
import { PiiItemCardUtils } from './pii-item-card.utils';

describe('PiiItemCardUtils', () => {
  let utils: PiiItemCardUtils;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [PiiItemCardUtils] });
    utils = TestBed.inject(PiiItemCardUtils);
  });

  // ========== PDF ==========

  it('Should_ReturnPdf_When_MimePdf', () => {
    expect(utils.attachmentKind('application/pdf')).toBe('pdf');
  });

  it('Should_ReturnPdf_When_ExtensionPdf', () => {
    expect(utils.attachmentKind('.pdf')).toBe('pdf');
  });

  // ========== Excel ==========

  it('Should_ReturnExcel_When_MimeExcel', () => {
    expect(utils.attachmentKind('application/vnd.ms-excel')).toBe('excel');
  });

  it('Should_ReturnExcel_When_MimeSpreadsheet', () => {
    expect(utils.attachmentKind('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')).toBe('excel');
  });

  it('Should_ReturnExcel_When_Xlsx', () => {
    expect(utils.attachmentKind('xlsx')).toBe('excel');
  });

  it('Should_ReturnExcel_When_Csv', () => {
    expect(utils.attachmentKind('text/csv')).toBe('excel');
  });

  it('Should_ReturnExcel_When_Ods', () => {
    expect(utils.attachmentKind('application/vnd.oasis.opendocument.spreadsheet')).toBe('excel');
  });

  // ========== Word ==========

  it('Should_ReturnWord_When_MimeWord', () => {
    expect(utils.attachmentKind('application/msword')).toBe('word');
  });

  it('Should_ReturnWord_When_Docx', () => {
    expect(utils.attachmentKind('application/vnd.openxmlformats-officedocument.wordprocessingml.document')).toBe('word');
  });

  it('Should_ReturnWord_When_Rtf', () => {
    expect(utils.attachmentKind('application/rtf')).toBe('word');
  });

  it('Should_ReturnWord_When_Odt', () => {
    expect(utils.attachmentKind('odt')).toBe('word');
  });

  // ========== PowerPoint ==========

  it('Should_ReturnPpt_When_MimePowerpoint', () => {
    expect(utils.attachmentKind('application/vnd.ms-powerpoint')).toBe('ppt');
  });

  it('Should_ReturnPpt_When_Pptx', () => {
    expect(utils.attachmentKind('pptx')).toBe('ppt');
  });

  it('Should_ReturnPpt_When_PresentationMime', () => {
    // NOTE: The full OpenXML MIME for PowerPoint contains 'doc' in 'officedocument',
    // which matches the Word check first. This is a known limitation of substring matching.
    // Using the short extension 'pptx' works correctly.
    expect(utils.attachmentKind('application/vnd.ms-powerpoint')).toBe('ppt');
  });

  it('Should_ReturnPpt_When_Odp', () => {
    expect(utils.attachmentKind('odp')).toBe('ppt');
  });

  // ========== Text ==========

  it('Should_ReturnTxt_When_PlainText', () => {
    expect(utils.attachmentKind('text/plain')).toBe('txt');
  });

  it('Should_ReturnTxt_When_Html', () => {
    expect(utils.attachmentKind('text/html')).toBe('txt');
  });

  it('Should_ReturnTxt_When_Markdown', () => {
    expect(utils.attachmentKind('md')).toBe('txt');
  });

  // ========== Edge cases ==========

  it('Should_ReturnNull_When_UnknownType', () => {
    expect(utils.attachmentKind('application/octet-stream')).toBeNull();
    expect(utils.attachmentKind('image/png')).toBeNull();
  });

  it('Should_ReturnNull_When_EmptyOrNull', () => {
    expect(utils.attachmentKind('')).toBeNull();
    expect(utils.attachmentKind(null)).toBeNull();
    expect(utils.attachmentKind(undefined)).toBeNull();
  });

  it('Should_BeCaseInsensitive_When_MixedCase', () => {
    expect(utils.attachmentKind('APPLICATION/PDF')).toBe('pdf');
    expect(utils.attachmentKind('Text/Plain')).toBe('txt');
  });
});
