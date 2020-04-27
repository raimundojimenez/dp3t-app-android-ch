/*
 * Created by Ubique Innovation AG
 * https://www.ubique.ch
 * Copyright (c) 2020. All rights reserved.
 */
package org.dpppt.android.app.inform;

import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.google.gson.Gson;

import org.dpppt.android.app.R;
import org.dpppt.android.app.inform.model.AccessTokenModel;
import org.dpppt.android.app.inform.model.AuthenticationCodeRequestModel;
import org.dpppt.android.app.inform.model.AuthenticationCodeResponseModel;
import org.dpppt.android.app.inform.networking.AuthCodeRepository;
import org.dpppt.android.app.inform.networking.InvalidCodeError;
import org.dpppt.android.app.inform.views.ChainedEditText;
import org.dpppt.android.app.storage.SecureStorage;
import org.dpppt.android.app.util.InfoDialog;
import org.dpppt.android.sdk.DP3T;
import org.dpppt.android.sdk.backend.ResponseCallback;
import org.dpppt.android.sdk.backend.models.ExposeeAuthMethodAuthorization;
import org.dpppt.android.sdk.internal.backend.ResponseException;

public class InformFragment extends Fragment {

	private static final long TIMEOUT_VALID_CODE = 1000 * 60 * 5;

	private static final String REGEX_CODE_PATTERN = "\\d{" + ChainedEditText.NUM_CHARACTERS + "}";

	private static final SimpleDateFormat ONSET_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

	private ChainedEditText authCodeInput;
	private AlertDialog progressDialog;

	private SecureStorage secureStorage;

	public static InformFragment newInstance() {
		return new InformFragment();
	}

	public InformFragment() {
		super(R.layout.fragment_inform);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		secureStorage = SecureStorage.getInstance(getContext());
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		Button buttonSend = view.findViewById(R.id.trigger_fragment_button_trigger);
		authCodeInput = view.findViewById(R.id.trigger_fragment_input);
		authCodeInput.addTextChangedListener(new ChainedEditText.ChainedEditTextListener() {
			@Override
			public void onTextChanged(String input) {
				buttonSend.setEnabled(input.matches(REGEX_CODE_PATTERN));
			}

			@Override
			public void onEditorSendAction() {
				if (buttonSend.isEnabled()) buttonSend.callOnClick();
			}
		});

		long lastRequestTime = secureStorage.getLastInformRequestTime();
		String lastCode = secureStorage.getLastInformCode();
		String lastToken = secureStorage.getLastInformToken();

		if (System.currentTimeMillis() - lastRequestTime < TIMEOUT_VALID_CODE) {
			authCodeInput.setText(lastCode);
		} else if (lastCode != null || lastToken != null) {
			secureStorage.clearInformTimeAndCodeAndToken();
		}

		buttonSend.setOnClickListener(v -> {
			setInvalidCodeErrorVisible(false);
			String authCode = authCodeInput.getText();

			progressDialog = createProgressDialog();
			if (System.currentTimeMillis() - lastRequestTime < TIMEOUT_VALID_CODE && lastToken != null) {
				Date onsetDate = getOnsetDate(lastToken);
				informExposed(onsetDate, getAuthorizationHeader(lastToken));
			} else {
				authenticateInput(authCode);
			}
		});

		view.findViewById(R.id.cancel_button).setOnClickListener(v -> {
			getActivity().onBackPressed();
		});

		view.findViewById(R.id.trigger_fragment_no_code_button).setOnClickListener(v -> {
			getParentFragmentManager().beginTransaction()
					.replace(R.id.inform_fragment_container, NoCodeFragment.newInstance())
					.addToBackStack(NoCodeFragment.class.getCanonicalName())
					.commit();
		});
	}

	private void authenticateInput(String authCode) {
		AuthCodeRepository authCodeRepository = new AuthCodeRepository(getContext());
		authCodeRepository.getAccessToken(new AuthenticationCodeRequestModel(authCode),
				new ResponseCallback<AuthenticationCodeResponseModel>() {
					@Override
					public void onSuccess(AuthenticationCodeResponseModel response) {
						String accessToken = response.getAccessToken();

						secureStorage.saveInformTimeAndCodeAndToken(authCode, accessToken);

						Date onsetDate = getOnsetDate(accessToken);
						if (onsetDate == null) showErrorDialog("Received unreadable jwt access-token.");
						informExposed(onsetDate, getAuthorizationHeader(accessToken));
					}

					@Override
					public void onError(Throwable throwable) {
						if (progressDialog != null && progressDialog.isShowing()) {
							progressDialog.dismiss();
						}
						if (throwable instanceof InvalidCodeError) {
							setInvalidCodeErrorVisible(true);
							return;
						}
						showErrorDialog(throwable.getLocalizedMessage());
					}
				});
	}

	private void informExposed(Date onsetDate, String authorizationHeader) {
		DP3T.sendIAmInfected(getContext(), onsetDate,
				new ExposeeAuthMethodAuthorization(authorizationHeader), new ResponseCallback<Void>() {
					@Override
					public void onSuccess(Void response) {
						if (progressDialog != null && progressDialog.isShowing()) {
							progressDialog.dismiss();
						}
						secureStorage.clearInformTimeAndCodeAndToken();
						getParentFragmentManager().beginTransaction()
								.replace(R.id.inform_fragment_container, ThankYouFragment.newInstance())
								.commit();
					}

					@Override
					public void onError(Throwable throwable) {
						if (progressDialog != null && progressDialog.isShowing()) {
							progressDialog.dismiss();
						}
						String error;
						error = getString(R.string.unexpected_error_title).replace("{ERROR}",
								throwable instanceof ResponseException ? throwable.getMessage() :
								throwable instanceof IOException ? throwable.getLocalizedMessage() : "");
						showErrorDialog(error);
						throwable.printStackTrace();
					}
				});
	}

	private Date getOnsetDate(String accessToken) {
		String[] tokenParts = accessToken.split("\\.");
		if (tokenParts.length < 3) {
			return null;
		}
		String payloadString = new String(Base64.decode(tokenParts[1], Base64.NO_WRAP), StandardCharsets.UTF_8);
		AccessTokenModel tokenModel = new Gson().fromJson(payloadString, AccessTokenModel.class);
		if (tokenModel != null && tokenModel.getOnset() != null) {
			try {
				return ONSET_DATE_FORMAT.parse(tokenModel.getOnset());
			} catch (ParseException e) {
				e.printStackTrace();
				return null;
			}
		}
		return null;
	}

	@Override
	public void onResume() {
		super.onResume();
		authCodeInput.requestFocus();
	}

	private void setInvalidCodeErrorVisible(boolean visible) {
		getView().findViewById(R.id.inform_invalid_code_error).setVisibility(visible ? View.VISIBLE : View.GONE);
		getView().findViewById(R.id.inform_input_text).setVisibility(visible ? View.GONE : View.VISIBLE);
	}

	private AlertDialog createProgressDialog() {
		return new AlertDialog.Builder(getContext())
				.setView(R.layout.dialog_loading)
				.show();
	}

	private void showErrorDialog(String error) {
		InfoDialog.newInstance(getString(R.string.unexpected_error_title).replace("{ERROR}", error))
				.show(getChildFragmentManager(), InfoDialog.class.getCanonicalName());
	}

	private String getAuthorizationHeader(String accessToken) {
		return "Bearer " + accessToken;
	}

}
