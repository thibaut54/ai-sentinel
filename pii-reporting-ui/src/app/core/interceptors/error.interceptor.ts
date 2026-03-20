import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { ErrorNotificationService, classifyError } from '../services/error-notification.service';

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const errorService = inject(ErrorNotificationService);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      const errorKey = error.error?.errorKey ?? 'error.internal';
      const status = error.status;
      const category = classifyError(status);

      errorService.notify({ errorKey, status, category });

      return throwError(() => error);
    })
  );
};
