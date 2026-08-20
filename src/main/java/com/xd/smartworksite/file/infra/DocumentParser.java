package com.xd.smartworksite.file.infra;

import com.xd.smartworksite.file.domain.FileObject;
import com.xd.smartworksite.file.domain.PreparedDocument;

public interface DocumentParser {

    boolean supports(String fileExt, String contentType);

    PreparedDocument parse(FileObject fileObject, byte[] content);
}
