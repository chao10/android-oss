package com.kickstarter.presenters;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Pair;
import android.widget.Toast;

import com.kickstarter.KsrApplication;
import com.kickstarter.R;
import com.kickstarter.libs.Presenter;
import com.kickstarter.libs.RxUtils;
import com.kickstarter.libs.StringUtils;
import com.kickstarter.models.CurrentUser;
import com.kickstarter.services.ApiError;
import com.kickstarter.services.ApiResponses.AccessTokenEnvelope;
import com.kickstarter.services.KickstarterClient;
import com.kickstarter.ui.activities.DiscoveryActivity;
import com.kickstarter.ui.activities.LoginActivity;
import com.kickstarter.ui.activities.TwoFactorActivity;

import javax.inject.Inject;

import retrofit.RetrofitError;
import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.android.widget.OnTextChangeEvent;
import rx.android.widget.WidgetObservable;
import rx.subjects.PublishSubject;

public class LoginPresenter extends Presenter<LoginActivity> {
  @Inject KickstarterClient client;
  @Inject CurrentUser currentUser;
  private final PublishSubject<Void> login = PublishSubject.create();

  @Override
  protected void onCreate(final Context context, Bundle savedInstanceState) {
    super.onCreate(context, savedInstanceState);
    ((KsrApplication) context.getApplicationContext()).component().inject(this);

    final Observable<OnTextChangeEvent> email = viewSubject
      .filter(v -> v != null)
      .flatMap(v -> WidgetObservable.text(v.email));

    final Observable<OnTextChangeEvent> password = viewSubject
      .filter(v -> v != null)
      .flatMap(v -> WidgetObservable.text(v.password));

    final Observable<Pair<String, String>> emailAndPassword =
      RxUtils.combineLatestPair(email, password)
      .map(v -> Pair.create(v.first.text().toString(), v.second.text().toString()));

    final Observable<Boolean> isValid = emailAndPassword
      .map(v -> LoginPresenter.isValid(v.first, v.second));

    final Observable<Pair<String, String>> submit = login
      .withLatestFrom(emailAndPassword, (l, v) -> v);

    subscribeTo(submit, this::submit);
    subscribeTo(isValid, valid -> view().setFormEnabled(valid));
  }

  private static boolean isValid(final String email, final String password) {
    return StringUtils.isEmail(email) && password.length() > 0;
  }

  public void login() {
    login.onNext(null);
  }

  private void submit(final Pair<String, String> emailPassword) {
    client.login(emailPassword.first, emailPassword.second)
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe(this::success, this::error);
  }

  private void success(final AccessTokenEnvelope envelope) {
    if (hasView()) {
      currentUser.set(envelope.user, envelope.access_token);
      Intent intent = new Intent(view(), DiscoveryActivity.class)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
      view().startActivity(intent);
    }
  }

  private void error(final Throwable e) {
    // TODO: Most of the logic here could be extracted, it will be shared
    // by many API error methods.
    if (!hasView()) {
      return;
    }

    if (e instanceof ApiError) {
      handleApiError((ApiError) e);
    } else if (e instanceof RetrofitError) {
      RetrofitError retrofitError = (RetrofitError) e;
      if (retrofitError.getKind() == RetrofitError.Kind.NETWORK) {
        toast(R.string.Unable_to_connect);
      } else {
        throw new RuntimeException(e);
      }
    } else {
      throw new RuntimeException(e);
    }
  }

  private void handleApiError(final ApiError apiError) {
    switch (apiError.errorEnvelope().ksrCode()) {
      case TFA_REQUIRED:
      case TFA_FAILED:
        startTwoFactorActivity();
        break;
      case INVALID_XAUTH_LOGIN:
        toast(R.string.Login_does_not_match_any_of_our_records);
        break;
      default:
        toast(R.string.Unable_to_login);
        break;
    }
  }

  private void startTwoFactorActivity() {
    Intent intent = new Intent(view(), TwoFactorActivity.class);
    // TODO: Fetching the details from the view seems a little dirty, it would be nice if we
    // could pass along the email and password that generated the event.
    intent.putExtra("email", view().email.getText().toString());
    intent.putExtra("password", view().password.getText().toString());
    view().startActivity(intent);
  }

  private void toast(final int id) {
    if (hasView()) {
      Toast toast = Toast.makeText(view(),
        view().getResources().getString(id),
        Toast.LENGTH_LONG);
      toast.show();
    }
  }
}
